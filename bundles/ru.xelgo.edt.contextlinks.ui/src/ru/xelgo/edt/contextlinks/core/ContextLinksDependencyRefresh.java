package ru.xelgo.edt.contextlinks.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Refreshes extension projects that depend on a changed linked extension project.
 */
public final class ContextLinksDependencyRefresh
{
    private static final long REFRESH_DELAY_MS = 2_500L;
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean scheduled = new AtomicBoolean();
    private static final Set<String> pendingProjectNames = ConcurrentHashMap.newKeySet();

    private static final IResourceChangeListener listener = ContextLinksDependencyRefresh::resourceChanged;

    private ContextLinksDependencyRefresh()
    {
        // Utility class.
    }

    public static void install(String source)
    {
        if (!installed.compareAndSet(false, true))
            return;

        ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
        ContextLinks.logWarning("EDT Context Links dependency refresh listener installed source=" + source); //$NON-NLS-1$
    }

    private static void resourceChanged(IResourceChangeEvent event)
    {
        IResourceDelta delta = event.getDelta();
        if (delta == null)
            return;

        Set<String> changedProjectNames = new LinkedHashSet<>();
        try
        {
            delta.accept(resourceDelta ->
            {
                if (isRelevantDelta(resourceDelta))
                {
                    IProject project = resourceDelta.getResource().getProject();
                    if (project != null)
                        changedProjectNames.add(project.getName());
                }
                return true;
            });
        }
        catch (CoreException e)
        {
            ContextLinks.logError("EDT Context Links dependency refresh cannot inspect resource delta", e); //$NON-NLS-1$
            return;
        }

        if (!changedProjectNames.isEmpty())
            requestRefresh(changedProjectNames);
    }

    private static boolean isRelevantDelta(IResourceDelta delta)
    {
        IResource resource = delta.getResource();
        if (resource == null || resource.getType() == IResource.ROOT)
            return false;

        IPath path = resource.getFullPath();
        if (path == null || path.segmentCount() < 2)
            return false;

        String topLevelFolder = path.segment(1);
        if (!"src".equals(topLevelFolder) && !"DT-INF".equals(topLevelFolder)) //$NON-NLS-1$ //$NON-NLS-2$
            return false;

        if (delta.getKind() != IResourceDelta.CHANGED)
            return true;

        int flags = delta.getFlags();
        int nonContentFlags = IResourceDelta.MARKERS | IResourceDelta.SYNC | IResourceDelta.DESCRIPTION;
        return (flags & ~nonContentFlags) != 0;
    }

    private static void requestRefresh(Set<String> changedProjectNames)
    {
        pendingProjectNames.addAll(changedProjectNames);
        if (!scheduled.compareAndSet(false, true))
            return;

        WorkspaceJob job = new WorkspaceJob("EDT Context Links dependency refresh") //$NON-NLS-1$
        {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor)
            {
                scheduled.set(false);
                Set<String> changedNames = drainPendingProjectNames();
                Set<IProject> projectsToBuild = collectProjectsToBuild(changedNames);
                if (projectsToBuild.isEmpty())
                    return Status.OK_STATUS;

                ContextLinks.logWarning("EDT Context Links dependency refresh changed=" + changedNames //$NON-NLS-1$
                    + " projects=" + describeProjects(projectsToBuild)); //$NON-NLS-1$

                monitor.beginTask(getName(), projectsToBuild.size());
                for (IProject project : projectsToBuild)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;

                    build(project, monitor);
                    monitor.worked(1);
                }
                monitor.done();

                if (!pendingProjectNames.isEmpty())
                    requestRefresh(Set.of());

                return Status.OK_STATUS;
            }
        };
        job.setRule(ResourcesPlugin.getWorkspace().getRoot());
        job.setSystem(true);
        job.schedule(REFRESH_DELAY_MS);
    }

    private static Set<String> drainPendingProjectNames()
    {
        Set<String> result = new LinkedHashSet<>(pendingProjectNames);
        pendingProjectNames.removeAll(result);
        return result;
    }

    private static Set<IProject> collectProjectsToBuild(Set<String> changedProjectNames)
    {
        Set<IProject> result = new LinkedHashSet<>();
        Set<String> changedExtensionNames = new LinkedHashSet<>();
        for (String changedProjectName : changedProjectNames)
        {
            IProject changedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(changedProjectName);
            if (ContextLinks.isExtensionProject(changedProject))
            {
                changedExtensionNames.add(changedProjectName);
                result.add(changedProject);
            }
        }

        if (changedExtensionNames.isEmpty())
            return result;

        Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(ContextLinks::isExtensionProject)
            .filter(project -> !changedExtensionNames.contains(project.getName()))
            .filter(project -> dependsOnAny(project, changedExtensionNames))
            .forEach(result::add);
        return result;
    }

    private static boolean dependsOnAny(IProject project, Set<String> changedProjectNames)
    {
        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        return linkedProjectNames.stream().anyMatch(changedProjectNames::contains);
    }

    private static void build(IProject project, IProgressMonitor monitor)
    {
        try
        {
            project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
        }
        catch (CoreException e)
        {
            ContextLinks.logError("EDT Context Links dependency refresh failed for " + project.getName(), e); //$NON-NLS-1$
        }
    }

    private static String describeProjects(Set<IProject> projects)
    {
        return projects.stream()
            .map(IProject::getName)
            .toList()
            .toString();
    }
}
