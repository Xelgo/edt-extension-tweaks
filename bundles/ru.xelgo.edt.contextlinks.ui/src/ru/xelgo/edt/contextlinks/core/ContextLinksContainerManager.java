package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
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
        List<IContainer> visibleContainers = new ArrayList<>(
            super.getVisibleContainers(description, resourceDescriptions));

        if (description == null || resourceDescriptions == null)
            return visibleContainers;

        IProject currentProject = getProject(description.getURI());
        if (isConfigurationProject(currentProject))
            return visibleContainers;

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(currentProject);
        if (currentProject == null || linkedProjectNames.isEmpty())
            return visibleContainers;

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

        return visibleContainers;
    }

    private void logContainers(IProject currentProject, URI resourceUri, String currentHandle,
        String standardVisible, Set<String> linkedProjectNames, List<String> addedHandles,
        List<String> unresolvedProjects)
    {
        String key = currentProject.getName() + "|" + currentHandle + "|" + linkedProjectNames //$NON-NLS-1$ //$NON-NLS-2$
            + "|" + addedHandles + "|" + unresolvedProjects; //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedContainerKeys.add(key))
        {
            ContextLinks.logWarning("EDT Context Links containers: project=" + currentProject.getName() //$NON-NLS-1$
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

    private IProject getProject(URI uri)
    {
        if (uri == null || !uri.isPlatformResource())
            return null;

        String platformPath = uri.toPlatformString(true);
        if (platformPath == null || platformPath.isEmpty())
            return null;

        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(IPath.fromOSString(platformPath));
        if (resource != null)
            return resource.getProject();

        String[] segments = platformPath.split("/"); //$NON-NLS-1$
        return segments.length > 0 && !segments[0].isEmpty()
            ? ResourcesPlugin.getWorkspace().getRoot().getProject(segments[0])
            : null;
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
            ContextLinks.logWarning("EDT Context Links cannot resolve container handle for " + uri //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private boolean isUsableHandle(IAllContainersState state, String handle)
    {
        return handle != null && !handle.isBlank() && !state.isEmpty(handle);
    }
}
