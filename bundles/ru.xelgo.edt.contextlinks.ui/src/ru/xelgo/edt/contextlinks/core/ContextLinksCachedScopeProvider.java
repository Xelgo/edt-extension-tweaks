package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
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
    private static final Set<String> loggedTypeScopeKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> debugLoggedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public IScope getTypeItemScope(IProject project)
    {
        IScope ownScope = super.getTypeItemScope(project);

        debugLogScope("getTypeItemScope", project, null, ownScope, null);

        if (ownScope == null || project == null || !project.isAccessible())
            return ownScope;

        if (isConfigurationProject(project))
            return ownScope;

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        if (linkedProjectNames.isEmpty())
            return ownScope;

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

            IScope linkedScope = super.getTypeItemScope(linkedProject);

            if (linkedScope == null && isExtensionProject(linkedProject))
            {
                linkedScope = buildScopeFromConfigurationResource(linkedProject);
            }

            debugLogScope("linked.scope", project, linkedProjectName, linkedScope, linkedScope == null ? "scope-null" : null);

            if (linkedScope != null)
            {
                compositeScope.addScope(new ResourceBackedScope(linkedScope));
                addedProjects.add(linkedProjectName);
            }
            else
            {
                missingProjects.add(linkedProjectName + " (super.getTypeItemScope returned null)");
            }
        }

        logTypeScope(project, linkedProjectNames, addedProjects, missingProjects);
        if (addedProjects.isEmpty())
            return ownScope;

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
                for (IEObjectDescription ignored : scope.getAllElements())
                {
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

    private static final class ResourceBackedScope
        implements IScope
    {
        private final IScope delegate;

        private ResourceBackedScope(IScope delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public IEObjectDescription getSingleElement(QualifiedName name)
        {
            IEObjectDescription description = delegate.getSingleElement(name);
            return safeDescription(description);
        }

        @Override
        public Iterable<IEObjectDescription> getElements(QualifiedName name)
        {
            return filter(delegate.getElements(name));
        }

        @Override
        public IEObjectDescription getSingleElement(EObject object)
        {
            IEObjectDescription description = delegate.getSingleElement(object);
            return safeDescription(description);
        }

        @Override
        public Iterable<IEObjectDescription> getElements(EObject object)
        {
            return filter(delegate.getElements(object));
        }

        @Override
        public Iterable<IEObjectDescription> getAllElements()
        {
            return filter(delegate.getAllElements());
        }

        private Iterable<IEObjectDescription> filter(Iterable<IEObjectDescription> descriptions)
        {
            List<IEObjectDescription> filteredDescriptions = new ArrayList<>();
            for (IEObjectDescription description : descriptions)
            {
                IEObjectDescription safeDescription = safeDescription(description);
                if (safeDescription != null)
                    filteredDescriptions.add(safeDescription);
            }
            return filteredDescriptions;
        }

        private IEObjectDescription safeDescription(IEObjectDescription description)
        {
            if (description == null)
                return null;

            if (description.getEObjectURI() == null || description.getEClass() == null)
                return null;

            EObject object = description.getEObjectOrProxy();
            if (object == null || object.eResource() == null)
                return null;

            return description;
        }
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
}
