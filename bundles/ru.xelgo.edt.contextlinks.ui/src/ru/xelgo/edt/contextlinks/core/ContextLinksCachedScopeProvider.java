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

import com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.modeling.xtext.scoping.ISliceFilter;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
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
    private static final ConcurrentHashMap<ProjectScopeKey, IScope> stableProjectScopes = new ConcurrentHashMap<>();

    public ContextLinksCachedScopeProvider()
    {
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
    }

    @Override
    public void clearTypeItemsScopes(IProject project)
    {
        if (ContextLinks.isDebugLoggingEnabled())
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [cache.clearTypeItems] project=" + describeProject(project)); //$NON-NLS-1$
        super.clearTypeItemsScopes(project);
    }

    @Override
    public void clearPropertyScopes(IProject project)
    {
        if (ContextLinks.isDebugLoggingEnabled())
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [cache.clearProperties] project=" + describeProject(project)); //$NON-NLS-1$
        super.clearPropertyScopes(project);
    }

    @Override
    public void addTypeItemScope(IProject project, IScope scope)
    {
        if (ContextLinks.isDebugLoggingEnabled())
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [cache.addTypeItem] project=" + describeProject(project) //$NON-NLS-1$
                + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        super.addTypeItemScope(project, scope);
        rememberStableProjectScope(project, ScopeKind.TYPE_ITEM, scope);
    }

    @Override
    public void addPropertyScope(IProject project, IScope scope)
    {
        if (ContextLinks.isDebugLoggingEnabled())
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [cache.addProperty] project=" + describeProject(project) //$NON-NLS-1$
                + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        super.addPropertyScope(project, scope);
        rememberStableProjectScope(project, ScopeKind.PROPERTY, scope);
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
        if (ContextLinks.isDebugLoggingEnabled())
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [cache.get." + kind + "] project=" //$NON-NLS-1$ //$NON-NLS-2$
                + describeProject(project) + " own=" + describeScope(ownScope)); //$NON-NLS-1$
        }

        if (project == null || ownScope == null || !project.isAccessible())
            return false;

        if (isConfigurationProject(project))
            return false;

        return !ContextLinks.getContextProjectNames(project).isEmpty();
    }

    private IScope composeLinkedScope(IProject project, IScope ownScope, ScopeKind kind)
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

            IScope linkedScope = kind.get(this, linkedProject);
            logScopeDetails(kind, project, linkedProject, linkedScope);
            if (linkedScope == null)
            {
                missingProjects.add(linkedProjectName + " (scope is null)"); //$NON-NLS-1$
                continue;
            }

            addedProjects.add(linkedProjectName);
        }

        logComposedScope(kind, project, linkedProjectNames, addedProjects, missingProjects);
        return new ContextLinksProjectScope(this, project, ownScope, linkedProjectNames, kind);
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
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = kind.name() + "|" + project.getName() + "|" + linkedProject.getName() + "|" //$NON-NLS-1$ //$NON-NLS-2$
            + describeScope(linkedScope) + "|" + countElements(linkedScope); //$NON-NLS-1$
        if (loggedScopeDetails.add(key))
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [cache.linked." + kind.logName + "] project=" //$NON-NLS-1$ //$NON-NLS-2$
                + project.getName() + " linked=" + linkedProject.getName() //$NON-NLS-1$
                + " scope=" + describeScope(linkedScope) + " elements=" + countElements(linkedScope)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void logComposedScope(ScopeKind kind, IProject project, Set<String> configuredProjects,
        List<String> addedProjects, List<String> missingProjects)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = project.getName() + "|" + new LinkedHashSet<>(configuredProjects) + "|" + addedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + missingProjects; //$NON-NLS-1$
        Set<String> targetLog = kind == ScopeKind.TYPE_ITEM ? loggedTypeScopeKeys : loggedPropertyScopeKeys;
        if (targetLog.add(key))
        {
            ContextLinks.logDebug("EDT Extension Tweaks " + kind.logName + " scope: project=" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
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
        IScope scope = super.getTypeItemScope(project);
        if (scope != null)
        {
            rememberStableProjectScope(project, ScopeKind.TYPE_ITEM, scope);
            return scope;
        }
        return getStableProjectScope(project, ScopeKind.TYPE_ITEM);
    }

    private IScope getDirectPropertyScope(IProject project)
    {
        IScope scope = super.getPropertyScope(project);
        if (scope != null)
        {
            rememberStableProjectScope(project, ScopeKind.PROPERTY, scope);
            return scope;
        }
        return getStableProjectScope(project, ScopeKind.PROPERTY);
    }

    private void rememberStableProjectScope(IProject project, ScopeKind kind, IScope scope)
    {
        if (project == null || !project.isAccessible() || kind == null || scope == null)
            return;

        stableProjectScopes.put(new ProjectScopeKey(project.getName(), kind), scope);
    }

    private IScope getStableProjectScope(IProject project, ScopeKind kind)
    {
        if (project == null || !project.isAccessible() || kind == null)
            return null;

        IScope scope = stableProjectScopes.get(new ProjectScopeKey(project.getName(), kind));
        return scope;
    }

    private static final class ContextLinksProjectScope
        implements IScope, ISlicedScope
    {
        private final ContextLinksCachedScopeProvider provider;
        private final IProject project;
        private final IScope ownScope;
        private final List<String> linkedProjectNames;
        private final ScopeKind kind;

        ContextLinksProjectScope(ContextLinksCachedScopeProvider provider, IProject project, IScope ownScope,
            Set<String> linkedProjectNames, ScopeKind kind)
        {
            this.provider = provider;
            this.project = project;
            this.ownScope = ownScope;
            this.linkedProjectNames = new ArrayList<>(linkedProjectNames);
            this.kind = kind;
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
            List<IScope> scopes = new ArrayList<>();
            for (String linkedProjectName : linkedProjectNames)
            {
                IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
                if (!provider.isUsableLinkedProject(project, linkedProject))
                    continue;

                IScope linkedScope = kind.get(provider, linkedProject);
                if (linkedScope != null)
                    scopes.add(linkedScope);
            }
            return scopes;
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

    private static final class ProjectScopeKey
    {
        private final String projectName;
        private final ScopeKind kind;

        ProjectScopeKey(String projectName, ScopeKind kind)
        {
            this.projectName = projectName;
            this.kind = kind;
        }

        @Override
        public int hashCode()
        {
            int result = projectName != null ? projectName.hashCode() : 0;
            result = 31 * result + (kind != null ? kind.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof ProjectScopeKey))
                return false;

            ProjectScopeKey other = (ProjectScopeKey)obj;
            return equals(projectName, other.projectName) && kind == other.kind;
        }

        private boolean equals(Object left, Object right)
        {
            return left == null ? right == null : left.equals(right);
        }
    }
}
