package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.modeling.xtext.scoping.CompositeScope;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
import com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Adds explicitly linked project scopes without owning or delaying EDT's cache lifecycle.
 */
public class ContextLinksCachedScopeProvider
    extends BslCachedScopeProvider
{
    private static final Set<String> loggedTypeScopeKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedPropertyScopeKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedScopeDetails = ConcurrentHashMap.newKeySet();

    public ContextLinksCachedScopeProvider()
    {
        ContextLinks.logWarning("EDT Context Links cached scope provider constructed"); //$NON-NLS-1$
    }

    @Override
    public void clearTypeItemsScopes(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.clearTypeItems] project=" + describeProject(project)); //$NON-NLS-1$
        super.clearTypeItemsScopes(project);
    }

    @Override
    public void clearPropertyScopes(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.clearProperties] project=" + describeProject(project)); //$NON-NLS-1$
        super.clearPropertyScopes(project);
    }

    @Override
    public void addTypeItemScope(IProject project, IScope scope)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.addTypeItem] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        super.addTypeItemScope(project, scope);
    }

    @Override
    public void addPropertyScope(IProject project, IScope scope)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.addProperty] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        super.addPropertyScope(project, scope);
    }

    @Override
    public IScope getTypeItemScope(IProject project)
    {
        IScope ownScope = super.getTypeItemScope(project);
        if (!canExtend(project, ownScope, "type-item")) //$NON-NLS-1$
            return ownScope;

        return composeLinkedScope(project, ownScope, ScopeKind.TYPE_ITEM);
    }

    @Override
    public IScope getPropertyScope(IProject project)
    {
        IScope ownScope = super.getPropertyScope(project);
        if (!canExtend(project, ownScope, "property")) //$NON-NLS-1$
            return ownScope;

        return composeLinkedScope(project, ownScope, ScopeKind.PROPERTY);
    }

    private boolean canExtend(IProject project, IScope ownScope, String kind)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.get." + kind + "] project=" //$NON-NLS-1$ //$NON-NLS-2$
            + describeProject(project) + " own=" + describeScope(ownScope)); //$NON-NLS-1$

        if (project == null || ownScope == null || !project.isAccessible())
            return false;

        if (isConfigurationProject(project))
            return false;

        return !ContextLinks.getContextProjectNames(project).isEmpty();
    }

    private IScope composeLinkedScope(IProject project, IScope ownScope, ScopeKind kind)
    {
        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        CompositeScope compositeScope = new CompositeScope(ISlicedScope.NULLSCOPE, true);
        compositeScope.addScope(ownScope);

        List<String> addedProjects = new ArrayList<>();
        List<String> missingProjects = new ArrayList<>();
        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!isUsableLinkedProject(project, linkedProject))
            {
                missingProjects.add(linkedProjectName + " (not accessible or same project)"); //$NON-NLS-1$
                continue;
            }

            IScope linkedScope = kind.get(this, linkedProject);
            logScopeDetails(kind, project, linkedProject, linkedScope);
            if (linkedScope == null)
            {
                missingProjects.add(linkedProjectName + " (scope is null)"); //$NON-NLS-1$
                continue;
            }

            compositeScope.addScope(linkedScope);
            addedProjects.add(linkedProjectName);
        }

        logComposedScope(kind, project, linkedProjectNames, addedProjects, missingProjects);
        if (addedProjects.isEmpty())
            return ownScope;

        return compositeScope;
    }

    private boolean isUsableLinkedProject(IProject project, IProject linkedProject)
    {
        return linkedProject != null && linkedProject.isAccessible() && !linkedProject.equals(project);
    }

    private boolean isConfigurationProject(IProject project)
    {
        if (project == null || !project.isAccessible())
            return false;

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
            return false;

        IV8Project v8Project = projectManager.getProject(project);
        return v8Project instanceof IConfigurationProject;
    }

    private void logScopeDetails(ScopeKind kind, IProject project, IProject linkedProject, IScope linkedScope)
    {
        String key = kind.name() + "|" + project.getName() + "|" + linkedProject.getName() + "|" //$NON-NLS-1$ //$NON-NLS-2$
            + describeScope(linkedScope) + "|" + countElements(linkedScope); //$NON-NLS-1$
        if (loggedScopeDetails.add(key))
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.linked." + kind.logName + "] project=" //$NON-NLS-1$ //$NON-NLS-2$
                + project.getName() + " linked=" + linkedProject.getName() //$NON-NLS-1$
                + " scope=" + describeScope(linkedScope) + " elements=" + countElements(linkedScope)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void logComposedScope(ScopeKind kind, IProject project, Set<String> configuredProjects,
        List<String> addedProjects, List<String> missingProjects)
    {
        String key = project.getName() + "|" + new LinkedHashSet<>(configuredProjects) + "|" + addedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + missingProjects; //$NON-NLS-1$
        Set<String> targetLog = kind == ScopeKind.TYPE_ITEM ? loggedTypeScopeKeys : loggedPropertyScopeKeys;
        if (targetLog.add(key))
        {
            ContextLinks.logWarning("EDT Context Links " + kind.logName + " scope: project=" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ", configured=" + configuredProjects //$NON-NLS-1$
                + ", added=" + addedProjects //$NON-NLS-1$
                + ", missing=" + missingProjects); //$NON-NLS-1$
        }
    }

    private int countElements(IScope scope)
    {
        if (scope == null)
            return -1;

        int count = 0;
        for (IEObjectDescription description : scope.getAllElements())
        {
            if (description != null)
                count++;
        }
        return count;
    }

    private String describeProject(IProject project)
    {
        if (project == null)
            return "NULL"; //$NON-NLS-1$
        return project.getName() + "{accessible=" + project.isAccessible() + "}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String describeScope(IScope scope)
    {
        if (scope == null)
            return "NULL"; //$NON-NLS-1$
        return scope.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(scope)); //$NON-NLS-1$
    }

    private enum ScopeKind
    {
        TYPE_ITEM("type-item") //$NON-NLS-1$
        {
            @Override
            IScope get(ContextLinksCachedScopeProvider provider, IProject project)
            {
                return provider.getDirectTypeItemScope(project);
            }
        },
        PROPERTY("property") //$NON-NLS-1$
        {
            @Override
            IScope get(ContextLinksCachedScopeProvider provider, IProject project)
            {
                return provider.getDirectPropertyScope(project);
            }
        };

        private final String logName;

        ScopeKind(String logName)
        {
            this.logName = logName;
        }

        abstract IScope get(ContextLinksCachedScopeProvider provider, IProject project);
    }

    private IScope getDirectTypeItemScope(IProject project)
    {
        return super.getTypeItemScope(project);
    }

    private IScope getDirectPropertyScope(IProject project)
    {
        return super.getPropertyScope(project);
    }
}
