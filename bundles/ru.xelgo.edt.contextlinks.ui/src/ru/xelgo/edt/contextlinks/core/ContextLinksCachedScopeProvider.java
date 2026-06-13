package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.v8.dt.bsl.model.Block;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.util.Environments;
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
    private static final Set<String> loggedModuleScopeKeys = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<ModuleScopeKey, IScope> moduleScopes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ModuleScopeKey, String> moduleScopeVersions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> projectScopeVersions = new ConcurrentHashMap<>();

    public ContextLinksCachedScopeProvider()
    {
        ContextLinks.logWarning("EDT Context Links cached scope provider constructed"); //$NON-NLS-1$
    }

    @Override
    public void clearTypeItemsScopes(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.clearTypeItems] project=" + describeProject(project)); //$NON-NLS-1$
        super.clearTypeItemsScopes(project);
        bumpProjectScopeVersion(project, "clear-type-item"); //$NON-NLS-1$
    }

    @Override
    public void clearPropertyScopes(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.clearProperties] project=" + describeProject(project)); //$NON-NLS-1$
        super.clearPropertyScopes(project);
        bumpProjectScopeVersion(project, "clear-property"); //$NON-NLS-1$
    }

    @Override
    public void addTypeItemScope(IProject project, IScope scope)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.addTypeItem] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        super.addTypeItemScope(project, scope);
        bumpProjectScopeVersion(project, "add-type-item"); //$NON-NLS-1$
    }

    @Override
    public void addPropertyScope(IProject project, IScope scope)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.addProperty] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        super.addPropertyScope(project, scope);
        bumpProjectScopeVersion(project, "add-property"); //$NON-NLS-1$
    }

    @Override
    public void addScope(Block block, Environments environments, BslCachedScopeType scopeType, IScope scope)
    {
        bumpProjectScopeVersion(projectFromBlock(block), "add-module-scope-" + scopeType); //$NON-NLS-1$
        rememberModuleScope(block, environments, scopeType, scope);
        super.addScope(block, environments, scopeType, scope);
    }

    @Override
    public IScope getScope(Block block, Environments environments, BslCachedScopeType scopeType)
    {
        IScope scope = super.getScope(block, environments, scopeType);
        if (scope != null && isModuleScopeCurrent(block, environments, scopeType))
            return scope;

        IScope mirroredScope = moduleScopes.get(new ModuleScopeKey(blockUniqueName(block), environments, scopeType));
        if (mirroredScope != null && !isModuleScopeCurrent(block, environments, scopeType))
            mirroredScope = null;

        logModuleScope("get-fallback", block, environments, scopeType, mirroredScope); //$NON-NLS-1$
        return mirroredScope;
    }

    @Override
    public void clearScopes(Module module)
    {
        int removedCount = forgetModuleScope(module != null ? module.getUniqueName() : null);
        if (module != null)
            module.allMethods().forEach(method -> forgetModuleScope(method.getUniqueName()));

        ContextLinks.logDebug("EDT Context Links DEBUG [cache.module.clear] module=" + blockUniqueName(module) //$NON-NLS-1$
            + " removed=" + removedCount); //$NON-NLS-1$
        bumpProjectScopeVersion(projectFromBlock(module), "clear-module-scopes"); //$NON-NLS-1$
        super.clearScopes(module);
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

    private void rememberModuleScope(Block block, Environments environments, BslCachedScopeType scopeType, IScope scope)
    {
        ModuleScopeKey key = new ModuleScopeKey(blockUniqueName(block), environments, scopeType);
        if (!key.isUsable() || scope == null)
            return;

        moduleScopes.put(key, scope);
        moduleScopeVersions.put(key, moduleScopeVersion(block));
        logModuleScope("add", block, environments, scopeType, scope); //$NON-NLS-1$
    }

    private int forgetModuleScope(String blockName)
    {
        if (blockName == null || blockName.isBlank())
            return 0;

        int before = moduleScopes.size();
        moduleScopes.keySet().removeIf(key -> blockName.equals(key.blockName));
        moduleScopeVersions.keySet().removeIf(key -> blockName.equals(key.blockName));
        return before - moduleScopes.size();
    }

    private boolean isModuleScopeCurrent(Block block, Environments environments, BslCachedScopeType scopeType)
    {
        ModuleScopeKey key = new ModuleScopeKey(blockUniqueName(block), environments, scopeType);
        if (!key.isUsable())
            return true;

        String cachedVersion = moduleScopeVersions.get(key);
        if (cachedVersion == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.module.untracked] block=" + blockUniqueName(block) //$NON-NLS-1$
                + " env=" + environments + " type=" + scopeType //$NON-NLS-1$ //$NON-NLS-2$
                + " current=" + moduleScopeVersion(block)); //$NON-NLS-1$
            return false;
        }

        String currentVersion = moduleScopeVersion(block);
        boolean current = cachedVersion.equals(currentVersion);
        if (!current)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.module.stale] block=" + blockUniqueName(block) //$NON-NLS-1$
                + " env=" + environments + " type=" + scopeType //$NON-NLS-1$ //$NON-NLS-2$
                + " cached=" + cachedVersion + " current=" + currentVersion); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return current;
    }

    private void bumpProjectScopeVersion(IProject project, String reason)
    {
        if (project == null || !project.isAccessible())
            return;

        long version = projectScopeVersions.computeIfAbsent(project.getName(), name -> new AtomicLong()).incrementAndGet();
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.project.version] project=" + project.getName() //$NON-NLS-1$
            + " version=" + version + " reason=" + reason); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String moduleScopeVersion(Block block)
    {
        IProject project = projectFromBlock(block);
        if (project == null || !project.isAccessible())
            return "project=NULL"; //$NON-NLS-1$

        StringBuilder builder = new StringBuilder();
        appendProjectVersion(builder, project.getName());
        for (String linkedProjectName : ContextLinks.getContextProjectNames(project))
            appendProjectVersion(builder, linkedProjectName);
        return builder.toString();
    }

    private void appendProjectVersion(StringBuilder builder, String projectName)
    {
        if (builder.length() > 0)
            builder.append('|');

        AtomicLong version = projectScopeVersions.get(projectName);
        builder.append(projectName).append('=').append(version != null ? version.get() : 0);
    }

    private IProject projectFromBlock(Block block)
    {
        String uniqueName = blockUniqueName(block);
        if (uniqueName == null)
            return null;

        String prefix = "platform:/resource/"; //$NON-NLS-1$
        if (!uniqueName.startsWith(prefix))
            return null;

        int projectStart = prefix.length();
        int projectEnd = uniqueName.indexOf('/', projectStart);
        if (projectEnd <= projectStart)
            return null;

        return ResourcesPlugin.getWorkspace().getRoot().getProject(uniqueName.substring(projectStart, projectEnd));
    }

    private void logModuleScope(String phase, Block block, Environments environments, BslCachedScopeType scopeType,
        IScope scope)
    {
        String key = phase + "|" + blockUniqueName(block) + "|" + environments + "|" + scopeType //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "|" + describeScope(scope) + "|" + countElements(scope); //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedModuleScopeKeys.add(key))
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.module." + phase + "] block=" //$NON-NLS-1$ //$NON-NLS-2$
                + blockUniqueName(block) + " env=" + environments + " type=" + scopeType //$NON-NLS-1$ //$NON-NLS-2$
                + " scope=" + describeScope(scope) + " elements=" + countElements(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private String blockUniqueName(Block block)
    {
        return block != null ? block.getUniqueName() : null;
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

    private static final class ModuleScopeKey
    {
        private final String blockName;
        private final Environments environments;
        private final BslCachedScopeType scopeType;

        ModuleScopeKey(String blockName, Environments environments, BslCachedScopeType scopeType)
        {
            this.blockName = blockName;
            this.environments = environments;
            this.scopeType = scopeType;
        }

        boolean isUsable()
        {
            return blockName != null && !blockName.isBlank() && environments != null && scopeType != null;
        }

        @Override
        public int hashCode()
        {
            int result = blockName != null ? blockName.hashCode() : 0;
            result = 31 * result + (environments != null ? environments.hashCode() : 0);
            result = 31 * result + (scopeType != null ? scopeType.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof ModuleScopeKey))
                return false;

            ModuleScopeKey other = (ModuleScopeKey)obj;
            return equals(blockName, other.blockName)
                && equals(environments, other.environments)
                && scopeType == other.scopeType;
        }

        private boolean equals(Object left, Object right)
        {
            return left == null ? right == null : left.equals(right);
        }
    }
}
