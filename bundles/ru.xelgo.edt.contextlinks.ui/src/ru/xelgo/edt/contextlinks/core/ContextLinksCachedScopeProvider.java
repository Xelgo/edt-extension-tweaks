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
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Reuses EDT cached metadata type scopes and composes selected project scopes into the current project.
 */
public class ContextLinksCachedScopeProvider
    extends BslCachedScopeProvider
{
    private static final int[] NULL_SCOPE_RETRY_DELAYS_MS = { 25, 75 };

    private static final Set<String> loggedTypeScopeKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedPropertyScopeKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> debugLoggedKeys = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, IScope> lastKnownTypeItemScopes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, IScope> lastKnownPropertyScopes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> pendingTypeItemDependents = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> pendingPropertyDependents = new ConcurrentHashMap<>();

    public ContextLinksCachedScopeProvider()
    {
        ContextLinks.logWarning("EDT Context Links cached scope provider constructed"); //$NON-NLS-1$
    }

    @Override
    public void clearTypeItemsScopes(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.clearTypeItems] project=" + describeProject(project) //$NON-NLS-1$
            + " keepingLastKnown=" + hasLastKnown(lastKnownTypeItemScopes, project)); //$NON-NLS-1$
        super.clearTypeItemsScopes(project);
    }

    @Override
    public void clearPropertyScopes(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.clearProperties] project=" + describeProject(project) //$NON-NLS-1$
            + " keepingLastKnown=" + hasLastKnown(lastKnownPropertyScopes, project)); //$NON-NLS-1$
        super.clearPropertyScopes(project);
    }

    @Override
    public void addTypeItemScope(IProject project, IScope scope)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.addTypeItem] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope)); //$NON-NLS-1$
        rememberScope(lastKnownTypeItemScopes, project, scope, "type-item"); //$NON-NLS-1$
        super.addTypeItemScope(project, scope);
        clearPendingDependentScopes(pendingTypeItemDependents, project, "type-item"); //$NON-NLS-1$
    }

    @Override
    public void addPropertyScope(IProject project, IScope scope)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.addProperty] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope)); //$NON-NLS-1$
        rememberScope(lastKnownPropertyScopes, project, scope, "property"); //$NON-NLS-1$
        super.addPropertyScope(project, scope);
        clearPendingDependentScopes(pendingPropertyDependents, project, "property"); //$NON-NLS-1$
    }

    @Override
    public IScope getTypeItemScope(IProject project)
    {
        if (project == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.enter] project=NULL result=NULL"); //$NON-NLS-1$
            return null;
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.enter] project=" + describeProject(project)); //$NON-NLS-1$
        IScope ownScope = getSuperTypeItemScopeWithRetry(project, "own"); //$NON-NLS-1$
        if (ownScope == null)
            ownScope = getLastKnownScope(lastKnownTypeItemScopes, project, "type-item", "own"); //$NON-NLS-1$ //$NON-NLS-2$

        debugLogScope("getTypeItemScope", project, null, ownScope, null);

        if (ownScope == null || !project.isAccessible())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.exit] project=" + describeProject(project) //$NON-NLS-1$
                + " result=" + describeScope(ownScope) + " reason=" //$NON-NLS-1$ //$NON-NLS-2$
                + (ownScope == null ? "own-scope-null" : "project-not-accessible")); //$NON-NLS-1$ //$NON-NLS-2$
            return ownScope;
        }

        if (isConfigurationProject(project))
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.exit] project=" + project.getName() //$NON-NLS-1$
                + " result=own reason=configuration-project"); //$NON-NLS-1$
            return ownScope;
        }

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        if (linkedProjectNames.isEmpty())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.exit] project=" + project.getName() //$NON-NLS-1$
                + " result=own reason=no-linked-projects"); //$NON-NLS-1$
            return ownScope;
        }

        List<String> addedProjects = new ArrayList<>();
        List<String> missingProjects = new ArrayList<>();
        CompositeScope compositeScope = new CompositeScope(ISlicedScope.NULLSCOPE, true);
        compositeScope.addScope(ownScope);

        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!linkedProject.isAccessible() || linkedProject.equals(project))
            {
                debugLogScope("linked.inaccessible", project, linkedProjectName, null, "not accessible or equals current");
                continue;
            }

            IScope linkedScope = getSuperTypeItemScopeWithRetry(linkedProject, "linked:" + project.getName()); //$NON-NLS-1$
            boolean lastKnownLinkedScope = false;
            if (linkedScope == null)
            {
                linkedScope = getLastKnownScope(lastKnownTypeItemScopes, linkedProject, "type-item", //$NON-NLS-1$
                    "linked:" + project.getName()); //$NON-NLS-1$
                lastKnownLinkedScope = linkedScope != null;
            }

            if (linkedScope == null && isExtensionProject(linkedProject))
            {
                linkedScope = buildScopeFromConfigurationResource(linkedProject);
            }

            debugLogScope("linked.scope", project, linkedProjectName, linkedScope, linkedScope == null ? "scope-null" : null);

            if (linkedScope != null)
            {
                compositeScope.addScope(linkedScope);
                addedProjects.add(lastKnownLinkedScope ? linkedProjectName + " (last-known)" : linkedProjectName); //$NON-NLS-1$
            }
            else
            {
                missingProjects.add(linkedProjectName + " (super.getTypeItemScope returned null)");
                rememberPendingDependent(pendingTypeItemDependents, linkedProjectName, project.getName(), "type-item"); //$NON-NLS-1$
            }
        }

        logTypeScope(project, linkedProjectNames, addedProjects, missingProjects);
        if (addedProjects.isEmpty())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.exit] project=" + project.getName() //$NON-NLS-1$
                + " result=own reason=no-added-projects"); //$NON-NLS-1$
            return ownScope;
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [cache.getTypeItem.exit] project=" + project.getName() //$NON-NLS-1$
            + " result=composite added=" + addedProjects); //$NON-NLS-1$
        return compositeScope;
    }

    @Override
    public IScope getPropertyScope(IProject project)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.getProperty.enter] project=" + describeProject(project)); //$NON-NLS-1$
        if (project == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getProperty.exit] project=NULL scope=NULL"); //$NON-NLS-1$
            return null;
        }

        IScope scope = getSuperPropertyScopeWithRetry(project, "own"); //$NON-NLS-1$
        if (scope == null)
            scope = getLastKnownScope(lastKnownPropertyScopes, project, "property", "own"); //$NON-NLS-1$ //$NON-NLS-2$
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.getProperty.exit] project=" + describeProject(project) //$NON-NLS-1$
            + " scope=" + describeScope(scope)); //$NON-NLS-1$

        if (scope == null || !project.isAccessible())
            return scope;

        if (isConfigurationProject(project))
            return scope;

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        if (linkedProjectNames.isEmpty())
            return scope;

        List<String> addedProjects = new ArrayList<>();
        List<String> missingProjects = new ArrayList<>();
        CompositeScope compositeScope = new CompositeScope(ISlicedScope.NULLSCOPE, true);
        compositeScope.addScope(scope);

        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!linkedProject.isAccessible() || linkedProject.equals(project))
            {
                debugLogScope("linked.property.inaccessible", project, linkedProjectName, null, //$NON-NLS-1$
                    "not accessible or equals current"); //$NON-NLS-1$
                continue;
            }

            IScope linkedScope = getSuperPropertyScopeWithRetry(linkedProject, "linked:" + project.getName()); //$NON-NLS-1$
            boolean lastKnownLinkedScope = false;
            if (linkedScope == null)
            {
                linkedScope = getLastKnownScope(lastKnownPropertyScopes, linkedProject, "property", //$NON-NLS-1$
                    "linked:" + project.getName()); //$NON-NLS-1$
                lastKnownLinkedScope = linkedScope != null;
            }
            debugLogScope("linked.property.scope", project, linkedProjectName, linkedScope, //$NON-NLS-1$
                linkedScope == null ? "scope-null" : null); //$NON-NLS-1$

            if (linkedScope != null)
            {
                compositeScope.addScope(linkedScope);
                addedProjects.add(lastKnownLinkedScope ? linkedProjectName + " (last-known)" : linkedProjectName); //$NON-NLS-1$
            }
            else
            {
                missingProjects.add(linkedProjectName + " (super.getPropertyScope returned null)"); //$NON-NLS-1$
                rememberPendingDependent(pendingPropertyDependents, linkedProjectName, project.getName(), "property"); //$NON-NLS-1$
            }
        }

        logPropertyScope(project, linkedProjectNames, addedProjects, missingProjects);
        if (addedProjects.isEmpty())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.getProperty.composite.exit] project=" //$NON-NLS-1$
                + project.getName() + " result=own reason=no-added-projects"); //$NON-NLS-1$
            return scope;
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [cache.getProperty.composite.exit] project=" //$NON-NLS-1$
            + project.getName() + " result=composite added=" + addedProjects); //$NON-NLS-1$
        return compositeScope;
    }

    private boolean isExtensionProject(IProject project)
    {
        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
            return false;

        IV8Project v8Project = projectManager.getProject(project);
        return v8Project != null && !(v8Project instanceof IConfigurationProject);
    }

    private IScope buildScopeFromConfigurationResource(IProject extensionProject)
    {
        return BslGlobalScopeProviderDelegate.getScopeFromProject(extensionProject);
    }

    private IScope getSuperTypeItemScopeWithRetry(IProject project, String reason)
    {
        IScope scope = super.getTypeItemScope(project);
        if (scope != null)
            return scope;

        return retryNullScope(project, "type-item", reason, () -> super.getTypeItemScope(project)); //$NON-NLS-1$
    }

    private IScope getSuperPropertyScopeWithRetry(IProject project, String reason)
    {
        IScope scope = super.getPropertyScope(project);
        if (scope != null)
            return scope;

        return retryNullScope(project, "property", reason, () -> super.getPropertyScope(project)); //$NON-NLS-1$
    }

    private IScope retryNullScope(IProject project, String kind, String reason, ScopeLookup lookup)
    {
        for (int attempt = 0; attempt < NULL_SCOPE_RETRY_DELAYS_MS.length; attempt++)
        {
            int delayMs = NULL_SCOPE_RETRY_DELAYS_MS[attempt];
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.retry.wait] kind=" + kind //$NON-NLS-1$
                + " project=" + describeProject(project) + " reason=" + reason //$NON-NLS-1$ //$NON-NLS-2$
                + " attempt=" + (attempt + 1) + " delayMs=" + delayMs); //$NON-NLS-1$ //$NON-NLS-2$
            sleep(delayMs);

            IScope scope = lookup.get();
            if (scope != null)
            {
                ContextLinks.logDebug("EDT Context Links DEBUG [cache.retry.hit] kind=" + kind //$NON-NLS-1$
                    + " project=" + describeProject(project) + " reason=" + reason //$NON-NLS-1$ //$NON-NLS-2$
                    + " attempt=" + (attempt + 1) + " scope=" + describeScope(scope)); //$NON-NLS-1$ //$NON-NLS-2$
                return scope;
            }
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [cache.retry.miss] kind=" + kind //$NON-NLS-1$
            + " project=" + describeProject(project) + " reason=" + reason); //$NON-NLS-1$
        return null;
    }

    private void sleep(int delayMs)
    {
        try
        {
            Thread.sleep(delayMs);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.retry.interrupted] delayMs=" + delayMs); //$NON-NLS-1$
        }
    }

    private void rememberScope(ConcurrentHashMap<String, IScope> scopes, IProject project, IScope scope, String kind)
    {
        if (project == null || scope == null)
            return;

        scopes.put(project.getName(), scope);
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.remember] kind=" + kind //$NON-NLS-1$
            + " project=" + project.getName() + " scope=" + describeScope(scope)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private IScope getLastKnownScope(ConcurrentHashMap<String, IScope> scopes, IProject project, String kind,
        String reason)
    {
        if (project == null || !project.isAccessible())
            return null;

        IScope scope = scopes.get(project.getName());
        if (scope != null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [cache.lastKnown] kind=" + kind //$NON-NLS-1$
                + " project=" + project.getName() + " reason=" + reason + " scope=" + describeScope(scope)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return scope;
    }

    private boolean hasLastKnown(ConcurrentHashMap<String, IScope> scopes, IProject project)
    {
        return project != null && scopes.containsKey(project.getName());
    }

    private void rememberPendingDependent(ConcurrentHashMap<String, Set<String>> pendingDependents,
        String sourceProjectName, String dependentProjectName, String kind)
    {
        pendingDependents.computeIfAbsent(sourceProjectName, key -> ConcurrentHashMap.newKeySet())
            .add(dependentProjectName);
        ContextLinks.logDebug("EDT Context Links DEBUG [cache.dependency.pending] kind=" + kind //$NON-NLS-1$
            + " source=" + sourceProjectName + " dependent=" + dependentProjectName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void clearPendingDependentScopes(ConcurrentHashMap<String, Set<String>> pendingDependents,
        IProject sourceProject, String kind)
    {
        if (sourceProject == null || !sourceProject.isAccessible())
            return;

        Set<String> dependentProjectNames = pendingDependents.remove(sourceProject.getName());
        if (dependentProjectNames == null || dependentProjectNames.isEmpty())
            return;

        List<String> clearedProjects = new ArrayList<>();
        List<String> skippedProjects = new ArrayList<>();
        for (String dependentProjectName : dependentProjectNames)
        {
            IProject dependentProject = ResourcesPlugin.getWorkspace().getRoot().getProject(dependentProjectName);
            if (!dependentProject.isAccessible() || dependentProject.equals(sourceProject))
            {
                skippedProjects.add(dependentProjectName + " (not accessible or same project)"); //$NON-NLS-1$
                continue;
            }

            if (!ContextLinks.getContextProjectNames(dependentProject).contains(sourceProject.getName()))
            {
                skippedProjects.add(dependentProjectName + " (link removed)"); //$NON-NLS-1$
                continue;
            }

            if ("property".equals(kind)) //$NON-NLS-1$
                super.clearPropertyScopes(dependentProject);
            else
                super.clearTypeItemsScopes(dependentProject);
            clearedProjects.add(dependentProjectName);
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [cache.dependency.ready] kind=" + kind //$NON-NLS-1$
            + " source=" + sourceProject.getName() + " cleared=" + clearedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + " skipped=" + skippedProjects); //$NON-NLS-1$
    }

    private void debugLogScope(String phase, IProject project, String linkedProjectName, IScope scope, String reason)
    {
        String key = phase + "|" + (project != null ? project.getName() : "null") + "|" +
            (linkedProjectName != null ? linkedProjectName : "") + "|" +
            (scope != null ? "scope-ok" : "scope-null") + "|" +
            (reason != null ? reason : "");

        if (debugLoggedKeys.add(key))
        {
            StringBuilder msg = new StringBuilder("EDT Context Links DEBUG [");
            msg.append(phase).append("] project=").append(project != null ? project.getName() : "null");
            if (linkedProjectName != null)
                msg.append(" linked=").append(linkedProjectName);
            msg.append(" scope=").append(scope != null ? "NOT_NULL" : "NULL");
            if (reason != null)
                msg.append(" reason=").append(reason);

            if (scope != null)
            {
            int count = 0;
            for (IEObjectDescription description : scope.getAllElements())
            {
                if (description != null)
                    count++;
            }
                msg.append(" elements=").append(count);
            }

            IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
            if (projectManager != null && linkedProjectName != null)
            {
                IProject linked = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
                if (linked != null)
                {
                    IV8Project v8proj = projectManager.getProject(linked);
                    msg.append(" v8project=").append(v8proj != null ? v8proj.getClass().getSimpleName() : "null");
                    msg.append(" configProject=").append(v8proj instanceof IConfigurationProject);
                }
            }

            ContextLinks.logDebug(msg.toString());
        }
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

    @FunctionalInterface
    private interface ScopeLookup
    {
        IScope get();
    }

    private boolean isConfigurationProject(IProject project)
    {
        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
            return false;

        IV8Project v8Project = projectManager.getProject(project);
        return v8Project instanceof IConfigurationProject;
    }

    private void logTypeScope(IProject project, Set<String> configuredProjects, List<String> addedProjects,
        List<String> missingProjects)
    {
        String key = project.getName() + "|" + new LinkedHashSet<>(configuredProjects) + "|" + addedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + missingProjects; //$NON-NLS-1$
        if (loggedTypeScopeKeys.add(key))
        {
            ContextLinks.logWarning("EDT Context Links type-item scope: project=" + project.getName() //$NON-NLS-1$
                + ", configured=" + configuredProjects //$NON-NLS-1$
                + ", added=" + addedProjects //$NON-NLS-1$
                + ", missing=" + missingProjects); //$NON-NLS-1$
        }
    }

    private void logPropertyScope(IProject project, Set<String> configuredProjects, List<String> addedProjects,
        List<String> missingProjects)
    {
        String key = project.getName() + "|" + new LinkedHashSet<>(configuredProjects) + "|" + addedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + missingProjects; //$NON-NLS-1$
        if (loggedPropertyScopeKeys.add(key))
        {
            ContextLinks.logWarning("EDT Context Links property scope: project=" + project.getName() //$NON-NLS-1$
                + ", configured=" + configuredProjects //$NON-NLS-1$
                + ", added=" + addedProjects //$NON-NLS-1$
                + ", missing=" + missingProjects); //$NON-NLS-1$
        }
    }
}
