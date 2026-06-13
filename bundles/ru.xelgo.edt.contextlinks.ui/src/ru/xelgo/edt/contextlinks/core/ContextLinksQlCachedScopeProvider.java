package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.modeling.xtext.scoping.ISliceFilter;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.ql.scoping.QlCachedScopeProvider;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Extends QL database-view scopes with explicitly linked extension projects.
 */
public class ContextLinksQlCachedScopeProvider
    extends QlCachedScopeProvider
{
    private static final Set<String> loggedDbViewScopeKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedDbViewDetails = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedStableDbViewFallbackKeys = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, IScope> stableDbViewScopes = new ConcurrentHashMap<>();

    public ContextLinksQlCachedScopeProvider()
    {
        ContextLinks.logDebug("EDT Context Links QL cached scope provider constructed"); //$NON-NLS-1$
    }

    @Override
    public void clearDbViewScopes(IDtProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [ql.cache.clearDbView] project=" //$NON-NLS-1$
            + describeDtProject(project));
        super.clearDbViewScopes(project);
    }

    @Override
    public void addDbViewScope(IDtProject project, IScope scope)
    {
        if (ContextLinks.isDebugLoggingEnabled())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [ql.cache.addDbView] project=" //$NON-NLS-1$
                + describeDtProject(project) + " scope=" + describeScope(scope) //$NON-NLS-1$
                + " elements=" + countElements(scope)); //$NON-NLS-1$
        }
        super.addDbViewScope(project, scope);
        rememberStableDbViewScope(project, scope);
    }

    @Override
    public IScope getDbViewScope(IDtProject project)
    {
        IScope ownScope = super.getDbViewScope(project);
        if (ownScope != null)
            rememberStableDbViewScope(project, ownScope);

        IProject workspaceProject = workspaceProject(project);
        if (!canExtend(workspaceProject, ownScope))
            return ownScope;

        return composeLinkedDbViewScope(workspaceProject, ownScope);
    }

    private boolean canExtend(IProject project, IScope ownScope)
    {
        if (ContextLinks.isDebugLoggingEnabled())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [ql.cache.getDbView] project=" //$NON-NLS-1$
                + describeProject(project) + " own=" + describeScope(ownScope)); //$NON-NLS-1$
        }

        if (project == null || ownScope == null || !project.isAccessible())
            return false;

        if (!ContextLinks.isExtensionProject(project))
            return false;

        return !ContextLinks.getContextProjectNames(project).isEmpty();
    }

    private IScope composeLinkedDbViewScope(IProject project, IScope ownScope)
    {
        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
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

            IDtProject linkedDtProject = dtProject(linkedProject);
            IScope linkedScope = getDirectDbViewScope(linkedDtProject);
            logDbViewDetails(project, linkedProject, linkedScope);
            if (linkedScope == null)
            {
                missingProjects.add(linkedProjectName + " (scope is null)"); //$NON-NLS-1$
                continue;
            }

            addedProjects.add(linkedProjectName);
        }

        logComposedDbViewScope(project, linkedProjectNames, addedProjects, missingProjects);
        return new ContextLinksQlDbViewScope(this, project, ownScope, linkedProjectNames);
    }

    private IScope getDirectDbViewScope(IDtProject project)
    {
        IScope scope = super.getDbViewScope(project);
        if (scope != null)
        {
            rememberStableDbViewScope(project, scope);
            return scope;
        }
        return getStableDbViewScope(project);
    }

    private void rememberStableDbViewScope(IDtProject project, IScope scope)
    {
        String projectName = projectName(project);
        if (projectName == null || scope == null)
            return;

        stableDbViewScopes.put(projectName, scope);
    }

    private IScope getStableDbViewScope(IDtProject project)
    {
        String projectName = projectName(project);
        if (projectName == null)
            return null;

        IScope scope = stableDbViewScopes.get(projectName);
        if (scope != null)
        {
            String key = projectName + "|" + describeScope(scope); //$NON-NLS-1$
            if (loggedStableDbViewFallbackKeys.add(key))
            {
                ContextLinks.logWarning("EDT Context Links QL stable db-view fallback project=" + projectName //$NON-NLS-1$
                    + " scope=" + describeScope(scope)); //$NON-NLS-1$
            }
        }
        return scope;
    }

    private List<IScope> getLinkedDbViewScopes(IProject project, List<String> linkedProjectNames)
    {
        List<IScope> scopes = new ArrayList<>();
        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!isUsableLinkedProject(project, linkedProject))
                continue;

            IScope linkedScope = getDirectDbViewScope(dtProject(linkedProject));
            if (linkedScope != null)
                scopes.add(linkedScope);
        }
        return scopes;
    }

    private boolean isUsableLinkedProject(IProject project, IProject linkedProject)
    {
        return linkedProject != null && linkedProject.isAccessible() && !linkedProject.equals(project);
    }

    private IDtProject dtProject(IProject project)
    {
        if (project == null || !project.isAccessible())
            return null;

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
            return null;

        IV8Project v8Project = projectManager.getProject(project);
        return v8Project != null ? v8Project.getDtProject() : null;
    }

    private IProject workspaceProject(IDtProject project)
    {
        return project != null ? project.getWorkspaceProject() : null;
    }

    private String projectName(IDtProject project)
    {
        IProject workspaceProject = workspaceProject(project);
        if (workspaceProject != null && workspaceProject.isAccessible())
            return workspaceProject.getName();
        return project != null ? project.getName() : null;
    }

    private void logDbViewDetails(IProject project, IProject linkedProject, IScope linkedScope)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = project.getName() + "|" + linkedProject.getName() + "|" //$NON-NLS-1$ //$NON-NLS-2$
            + describeScope(linkedScope) + "|" + countElements(linkedScope); //$NON-NLS-1$
        if (loggedDbViewDetails.add(key))
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [ql.cache.linked.db-view] project=" //$NON-NLS-1$
                + project.getName() + " linked=" + linkedProject.getName() //$NON-NLS-1$
                + " scope=" + describeScope(linkedScope) + " elements=" + countElements(linkedScope)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void logComposedDbViewScope(IProject project, Set<String> configuredProjects,
        List<String> addedProjects, List<String> missingProjects)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = project.getName() + "|" + new LinkedHashSet<>(configuredProjects) + "|" + addedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + missingProjects; //$NON-NLS-1$
        if (loggedDbViewScopeKeys.add(key))
        {
            ContextLinks.logDebug("EDT Context Links QL db-view scope: project=" + project.getName() //$NON-NLS-1$
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

    private String describeDtProject(IDtProject project)
    {
        if (project == null)
            return "NULL"; //$NON-NLS-1$
        return project.getName() + "{type=" + project.getType() + ", workspace=" //$NON-NLS-1$ //$NON-NLS-2$
            + describeProject(project.getWorkspaceProject()) + "}"; //$NON-NLS-1$
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

    private static final class ContextLinksQlDbViewScope
        implements IScope, ISlicedScope
    {
        private final ContextLinksQlCachedScopeProvider provider;
        private final IProject project;
        private final IScope ownScope;
        private final List<String> linkedProjectNames;

        ContextLinksQlDbViewScope(ContextLinksQlCachedScopeProvider provider, IProject project, IScope ownScope,
            Set<String> linkedProjectNames)
        {
            this.provider = provider;
            this.project = project;
            this.ownScope = ownScope;
            this.linkedProjectNames = new ArrayList<>(linkedProjectNames);
        }

        @Override
        public IEObjectDescription getSingleElement(QualifiedName name)
        {
            IEObjectDescription ownElement = getSingleElement(ownScope, name, null);
            if (ownElement != null)
                return ownElement;

            for (IScope linkedScope : getLinkedScopes())
            {
                IEObjectDescription linkedElement = getSingleElement(linkedScope, name, null);
                if (linkedElement != null)
                    return linkedElement;
            }
            return null;
        }

        @Override
        public IEObjectDescription getSingleElement(QualifiedName name, Collection<ISliceFilter> filters)
        {
            IEObjectDescription ownElement = getSingleElement(ownScope, name, filters);
            if (ownElement != null)
                return ownElement;

            for (IScope linkedScope : getLinkedScopes())
            {
                IEObjectDescription linkedElement = getSingleElement(linkedScope, name, filters);
                if (linkedElement != null)
                    return linkedElement;
            }
            return null;
        }

        @Override
        public Iterable<IEObjectDescription> getElements(QualifiedName name)
        {
            List<IEObjectDescription> result = new ArrayList<>();
            getElements(ownScope, name, null).forEach(result::add);
            for (IScope linkedScope : getLinkedScopes())
                getElements(linkedScope, name, null).forEach(result::add);
            return result;
        }

        @Override
        public Iterable<IEObjectDescription> getElements(QualifiedName name, Collection<ISliceFilter> filters)
        {
            List<IEObjectDescription> result = new ArrayList<>();
            getElements(ownScope, name, filters).forEach(result::add);
            for (IScope linkedScope : getLinkedScopes())
                getElements(linkedScope, name, filters).forEach(result::add);
            return result;
        }

        @Override
        public IEObjectDescription getSingleElement(EObject object)
        {
            IEObjectDescription ownElement = ownScope.getSingleElement(object);
            if (ownElement != null)
                return ownElement;

            for (IScope linkedScope : getLinkedScopes())
            {
                IEObjectDescription linkedElement = linkedScope.getSingleElement(object);
                if (linkedElement != null)
                    return linkedElement;
            }
            return null;
        }

        @Override
        public Iterable<IEObjectDescription> getElements(EObject object)
        {
            List<IEObjectDescription> result = new ArrayList<>();
            getElements(ownScope, object, null).forEach(result::add);
            for (IScope linkedScope : getLinkedScopes())
                getElements(linkedScope, object, null).forEach(result::add);
            return result;
        }

        @Override
        public Iterable<IEObjectDescription> getElements(EObject object, Collection<ISliceFilter> filters)
        {
            List<IEObjectDescription> result = new ArrayList<>();
            getElements(ownScope, object, filters).forEach(result::add);
            for (IScope linkedScope : getLinkedScopes())
                getElements(linkedScope, object, filters).forEach(result::add);
            return result;
        }

        @Override
        public Iterable<IEObjectDescription> getAllElements()
        {
            List<IEObjectDescription> result = new ArrayList<>();
            getAllElements(ownScope, null).forEach(result::add);
            for (IScope linkedScope : getLinkedScopes())
                getAllElements(linkedScope, null).forEach(result::add);
            return result;
        }

        @Override
        public Iterable<IEObjectDescription> getAllElements(Collection<ISliceFilter> filters)
        {
            List<IEObjectDescription> result = new ArrayList<>();
            getAllElements(ownScope, filters).forEach(result::add);
            for (IScope linkedScope : getLinkedScopes())
                getAllElements(linkedScope, filters).forEach(result::add);
            return result;
        }

        private List<IScope> getLinkedScopes()
        {
            return provider.getLinkedDbViewScopes(project, linkedProjectNames);
        }

        private IEObjectDescription getSingleElement(IScope scope, QualifiedName name, Collection<ISliceFilter> filters)
        {
            if (scope instanceof ISlicedScope && filters != null)
                return ((ISlicedScope)scope).getSingleElement(name, filters);
            return scope.getSingleElement(name);
        }

        private Iterable<IEObjectDescription> getElements(IScope scope, QualifiedName name,
            Collection<ISliceFilter> filters)
        {
            if (scope instanceof ISlicedScope && filters != null)
                return ((ISlicedScope)scope).getElements(name, filters);
            return scope.getElements(name);
        }

        private Iterable<IEObjectDescription> getElements(IScope scope, EObject object,
            Collection<ISliceFilter> filters)
        {
            if (scope instanceof ISlicedScope && filters != null)
                return ((ISlicedScope)scope).getElements(object, filters);
            return scope.getElements(object);
        }

        private Iterable<IEObjectDescription> getAllElements(IScope scope, Collection<ISliceFilter> filters)
        {
            if (scope instanceof ISlicedScope && filters != null)
                return ((ISlicedScope)scope).getAllElements(filters);
            return scope.getAllElements();
        }
    }
}
