package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.modeling.xtext.scoping.CompositeScope;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
import com._1c.g5.v8.dt.bsl.scoping.BslGlobalScopeProvider;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.wiring.ServiceAccess;
import com.google.common.base.Predicate;

/**
 * Extends the standard BSL global scope with type-item scopes from explicitly linked EDT projects.
 */
public class ContextLinksBslGlobalScopeProvider
    extends BslGlobalScopeProvider
{
    private static final Set<String> loggedKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> debugLoggedKeys = ConcurrentHashMap.newKeySet();

    public ContextLinksBslGlobalScopeProvider()
    {
        ContextLinks.logWarning("EDT Context Links global scope provider constructed"); //$NON-NLS-1$
    }

    @Override
    public IScope getScope(Resource resource, EReference reference, Predicate<IEObjectDescription> filter)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [global.enter] resource=" + describeResource(resource) //$NON-NLS-1$
            + " reference=" + describeReference(reference) + " filter=" + describeObject(filter)); //$NON-NLS-1$ //$NON-NLS-2$
        IScope ownScope = super.getScope(resource, reference, filter);
        ContextLinks.logDebug("EDT Context Links DEBUG [global.super] resource=" + describeResource(resource) //$NON-NLS-1$
            + " ownScope=" + describeObject(ownScope)); //$NON-NLS-1$
        if (!isTypeItemReference(reference) || resource == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] result=own reason=" //$NON-NLS-1$
                + (!isTypeItemReference(reference) ? "not-type-item-reference" : "resource-null")); //$NON-NLS-1$ //$NON-NLS-2$
            return ownScope;
        }

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] result=own reason=projectManager-null"); //$NON-NLS-1$
            return ownScope;
        }

        IV8Project currentV8Project = projectManager.getProject(resource.getURI());
        if (currentV8Project == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] result=own reason=currentV8Project-null uri=" //$NON-NLS-1$
                + resource.getURI());
            return ownScope;
        }

        IProject currentProject = currentV8Project.getProject();
        if (currentProject == null || !currentProject.isAccessible() || currentV8Project instanceof IConfigurationProject)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] result=own currentProject=" //$NON-NLS-1$
                + (currentProject != null ? currentProject.getName() : "NULL") + " reason=" //$NON-NLS-1$ //$NON-NLS-2$
                + (currentProject == null ? "project-null" //$NON-NLS-1$
                    : (!currentProject.isAccessible() ? "project-not-accessible" : "configuration-project"))); //$NON-NLS-1$ //$NON-NLS-2$
            return ownScope;
        }

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(currentProject);
        if (linkedProjectNames.isEmpty())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=own reason=no-linked-projects"); //$NON-NLS-1$
            return ownScope;
        }

        CompositeScope compositeScope = new CompositeScope(ISlicedScope.NULLSCOPE, true);
        if (ownScope != null)
            compositeScope.addScope(ownScope);

        List<String> addedProjects = new ArrayList<>();
        List<String> missingProjects = new ArrayList<>();
        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!linkedProject.isAccessible() || linkedProject.equals(currentProject))
            {
                debugLogGlobalScope("linked.inaccessible", currentProject, linkedProjectName, null, "not accessible or equals current");
                continue;
            }

            Resource linkedResource = getConfigurationResource(projectManager, linkedProject);
            debugLogGlobalScope("config.resource", currentProject, linkedProjectName, linkedResource,
                linkedResource == null ? "resource is null" : (linkedResource.equals(resource) ? "equals current resource" : "ok"));

            if (linkedResource == null || linkedResource.equals(resource))
            {
                missingProjects.add(linkedProjectName + " (no config resource)");
                continue;
            }

            IScope linkedScope = super.getScope(linkedResource, reference, filter);
            if (linkedScope == null)
            {
                missingProjects.add(linkedProjectName + " (scope is null)");
                continue;
            }

            compositeScope.addScope(linkedScope);
            addedProjects.add(linkedProjectName);
        }

        logGlobalScope(currentProject, linkedProjectNames, addedProjects, missingProjects);
        if (addedProjects.isEmpty())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=own reason=no-added-projects"); //$NON-NLS-1$
            return ownScope;
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [global.exit] project=" + currentProject.getName() //$NON-NLS-1$
            + " result=composite added=" + addedProjects); //$NON-NLS-1$
        return compositeScope;
    }

    private void debugLogGlobalScope(String phase, IProject project, String linkedProjectName, Resource resource, String reason)
    {
        String key = phase + "|" + project.getName() + "|" + linkedProjectName + "|" +
            (resource != null ? "res-ok" : "res-null") + "|" + (reason != null ? reason : "");

        if (debugLoggedKeys.add(key))
        {
            StringBuilder msg = new StringBuilder("EDT Context Links DEBUG [");
            msg.append(phase).append("] project=").append(project.getName());
            msg.append(" linked=").append(linkedProjectName);
            msg.append(" resource=").append(resource != null ? resource.getURI() : "NULL");
            msg.append(" reason=").append(reason != null ? reason : "n/a");

            IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
            if (projectManager != null)
            {
                IProject linked = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
                if (linked != null)
                {
                    IV8Project v8proj = projectManager.getProject(linked);
                    msg.append(" v8class=").append(v8proj != null ? v8proj.getClass().getName() : "null");
                    msg.append(" isConfigAware=").append(v8proj instanceof IConfigurationAware);
                    msg.append(" isDependent=").append(v8proj instanceof IDependentProject);
                    if (v8proj instanceof IDependentProject)
                    {
                        IConfigurationProject parent = ((IDependentProject)v8proj).getParent();
                        msg.append(" parent=").append(parent != null ? parent.getProject().getName() : "null");
                    }
                }
            }

            ContextLinks.logDebug(msg.toString());
        }
    }

    private boolean isTypeItemReference(EReference reference)
    {
        return reference != null
            && McorePackage.Literals.TYPE_ITEM.isSuperTypeOf(reference.getEReferenceType());
    }

    private String describeResource(Resource resource)
    {
        return resource != null ? String.valueOf(resource.getURI()) : "NULL"; //$NON-NLS-1$
    }

    private String describeReference(EReference reference)
    {
        if (reference == null)
            return "NULL"; //$NON-NLS-1$
        return reference.getName() + "->" + reference.getEReferenceType().getName(); //$NON-NLS-1$
    }

    private String describeObject(Object object)
    {
        if (object == null)
            return "NULL"; //$NON-NLS-1$
        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object)); //$NON-NLS-1$
    }

    private Resource getConfigurationResource(IV8ProjectManager projectManager, IProject project)
    {
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            debugLogGetConfig(project.getName(), "v8Project is null", null);
            return null;
        }

        Configuration configuration = null;
        if (v8Project instanceof IConfigurationAware)
        {
            configuration = ((IConfigurationAware)v8Project).getConfiguration();
            debugLogGetConfig(project.getName(), "IConfigurationAware", configuration);
        }
        else if (v8Project instanceof IDependentProject)
        {
            IConfigurationProject parent = ((IDependentProject)v8Project).getParent();
            debugLogGetConfig(project.getName(), "IDependentProject parent=" + (parent != null ? parent.getProject().getName() : "null"), parent);
            if (parent != null)
                configuration = parent.getConfiguration();
        }
        else
        {
            debugLogGetConfig(project.getName(), "unknown type: " + v8Project.getClass().getSimpleName(), null);
        }

        EObject configurationObject = configuration;
        return configurationObject != null ? configurationObject.eResource() : null;
    }

    private void debugLogGetConfig(String projectName, String path, Object result)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [getConfig] project=" + projectName + " path=" + path + " result=" + (result != null ? "ok" : "null"));
    }

    private void logGlobalScope(IProject project, Set<String> configuredProjects, List<String> addedProjects,
        List<String> missingProjects)
    {
        String key = project.getName() + "|" + new LinkedHashSet<>(configuredProjects) + "|" + addedProjects //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + missingProjects; //$NON-NLS-1$
        if (loggedKeys.add(key))
        {
            ContextLinks.logWarning("EDT Context Links global type-item scope: project=" + project.getName() //$NON-NLS-1$
                + ", configured=" + configuredProjects //$NON-NLS-1$
                + ", added=" + addedProjects //$NON-NLS-1$
                + ", missing=" + missingProjects); //$NON-NLS-1$
        }
    }
}
