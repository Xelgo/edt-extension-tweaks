package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IContainer;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.containers.IAllContainersState;

import com._1c.g5.v8.dt.bsl.resource.BslLightStateBasedContainerManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Extends EDT's own BSL container visibility with user-selected workspace projects.
 */
public class ContextLinksContainerManager
    extends BslLightStateBasedContainerManager
{
    private static final Set<String> loggedContainerKeys = ConcurrentHashMap.newKeySet();

    @Override
    public List<IContainer> getVisibleContainers(IResourceDescription description,
        IResourceDescriptions resourceDescriptions)
    {
        ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.enter] description=" //$NON-NLS-1$
            + describeDescription(description) + " resourceDescriptions=" + describeObject(resourceDescriptions)); //$NON-NLS-1$

        if (description == null || resourceDescriptions == null)
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.exit] result=[] reason=null-argument"); //$NON-NLS-1$
            return List.of();
        }

        List<IContainer> visibleContainers = new ArrayList<>(
            super.getVisibleContainers(description, resourceDescriptions));
        ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.super] resource=" + description.getURI() //$NON-NLS-1$
            + " count=" + visibleContainers.size()); //$NON-NLS-1$

        IProject currentProject = ContextLinks.getProject(description.getURI());
        if (isConfigurationProject(currentProject))
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=standard reason=configuration-project"); //$NON-NLS-1$
            return visibleContainers;
        }

        if (currentProject == null)
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.exit] result=standard reason=project-null"); //$NON-NLS-1$
            return visibleContainers;
        }

        if (ContextLinks.shouldSkipBslContextExtension("bsl-containers")) //$NON-NLS-1$
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=standard reason=build"); //$NON-NLS-1$
            return visibleContainers;
        }

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(currentProject);
        if (linkedProjectNames.isEmpty())
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=standard reason=no-linked-projects"); //$NON-NLS-1$
            return visibleContainers;
        }

        IAllContainersState state = getState(resourceDescriptions);
        String currentHandle = state.getContainerHandle(description.getURI());
        Set<String> knownHandles = new LinkedHashSet<>();
        if (currentHandle != null)
        {
            knownHandles.add(currentHandle);
            knownHandles.addAll(state.getVisibleContainerHandles(currentHandle));
        }

        List<String> addedHandles = new ArrayList<>();
        List<String> unresolvedProjects = new ArrayList<>();
        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!linkedProject.isAccessible() || linkedProject.equals(currentProject))
                continue;

            String linkedHandle = getContainerHandle(state, linkedProject);
            if (linkedHandle == null)
            {
                unresolvedProjects.add(linkedProjectName);
                continue;
            }

            if (knownHandles.add(linkedHandle))
            {
                IContainer linkedContainer = createContainer(linkedHandle, resourceDescriptions);
                if (!linkedContainer.isEmpty())
                {
                    visibleContainers.add(linkedContainer);
                    addedHandles.add(linkedProjectName + "=" + linkedHandle); //$NON-NLS-1$
                }
            }
        }

        logContainers(currentProject, description.getURI(), currentHandle,
            describeVisibleHandles(state, currentHandle), linkedProjectNames, addedHandles, unresolvedProjects);
        if (!addedHandles.isEmpty())
            ContextLinks.logScopeExtension("bsl-containers", currentProject, //$NON-NLS-1$
                "resource=" + description.getURI() + " added=" + addedHandles); //$NON-NLS-1$ //$NON-NLS-2$

        ContextLinks.logDebug("EDT Extension Tweaks DEBUG [containers.exit] project=" + currentProject.getName() //$NON-NLS-1$
            + " resultCount=" + visibleContainers.size() + " added=" + addedHandles //$NON-NLS-1$ //$NON-NLS-2$
            + " unresolved=" + unresolvedProjects); //$NON-NLS-1$
        return visibleContainers;
    }

    private void logContainers(IProject currentProject, URI resourceUri, String currentHandle,
        String standardVisible, Set<String> linkedProjectNames, List<String> addedHandles,
        List<String> unresolvedProjects)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = currentProject.getName() + "|" + currentHandle + "|" + linkedProjectNames //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + addedHandles + "|" + unresolvedProjects; //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedContainerKeys.add(key))
        {
            ContextLinks.logDebug("EDT Extension Tweaks containers: project=" + currentProject.getName() //$NON-NLS-1$
                + ", resource=" + resourceUri //$NON-NLS-1$
                + ", currentHandle=" + currentHandle //$NON-NLS-1$
                + ", standardVisible=" + standardVisible //$NON-NLS-1$
                + ", configured=" + linkedProjectNames //$NON-NLS-1$
                + ", added=" + addedHandles //$NON-NLS-1$
                + ", unresolved=" + unresolvedProjects); //$NON-NLS-1$
        }
    }

    private String describeVisibleHandles(IAllContainersState state, String currentHandle)
    {
        if (currentHandle == null)
            return "[]"; //$NON-NLS-1$
        return state.getVisibleContainerHandles(currentHandle).toString();
    }

    private String describeDescription(IResourceDescription description)
    {
        return description != null ? String.valueOf(description.getURI()) : "NULL"; //$NON-NLS-1$
    }

    private String describeObject(Object object)
    {
        if (object == null)
            return "NULL"; //$NON-NLS-1$
        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object)); //$NON-NLS-1$
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

    private String getContainerHandle(IAllContainersState state, IProject project)
    {
        List<URI> candidates = List.of(
            URI.createPlatformResourceURI(project.getName() + "/.project", true), //$NON-NLS-1$
            URI.createPlatformResourceURI(project.getName() + "/DT-INF/PROJECT.PMF", true)); //$NON-NLS-1$

        for (URI candidate : candidates)
        {
            String handle = getContainerHandleSafely(state, candidate);
            if (isUsableHandle(state, handle))
                return handle;
        }

        if (isUsableHandle(state, project.getName()))
            return project.getName();

        return null;
    }

    private String getContainerHandleSafely(IAllContainersState state, URI uri)
    {
        try
        {
            return state.getContainerHandle(uri);
        }
        catch (RuntimeException e)
        {
            ContextLinks.logWarning("EDT Extension Tweaks cannot resolve container handle for " + uri //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private boolean isUsableHandle(IAllContainersState state, String handle)
    {
        return handle != null && !handle.isBlank() && !state.isEmpty(handle);
    }
}
