package ru.xelgo.edt.contextlinks.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceVisitor;
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
    private static final long SYNTHETIC_DELTA_SUPPRESS_MS = 15_000L;
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean scheduled = new AtomicBoolean();
    private static final Set<String> pendingProjectNames = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> syntheticDeltaProjects = new ConcurrentHashMap<>();

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

        IProject project = resource.getProject();
        if (project != null && isSyntheticDelta(project.getName()))
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
                RefreshPlan plan = collectRefreshPlan(changedNames);
                if (plan.isEmpty())
                    return Status.OK_STATUS;

                ContextLinks.logWarning("EDT Context Links dependency refresh changed=" + changedNames //$NON-NLS-1$
                    + " changedProjects=" + describeProjects(plan.changedProjects) //$NON-NLS-1$
                    + " dependentProjects=" + describeProjects(plan.dependentProjects)); //$NON-NLS-1$

                monitor.beginTask(getName(), plan.size());
                for (IProject project : plan.changedProjects)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;

                    build(project, monitor);
                    monitor.worked(1);
                }
                for (IProject project : plan.dependentProjects)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;

                    touchProjectSource(project, monitor);
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

    private static RefreshPlan collectRefreshPlan(Set<String> changedProjectNames)
    {
        Set<IProject> changedProjects = new LinkedHashSet<>();
        Set<IProject> dependentProjects = new LinkedHashSet<>();
        Set<String> changedExtensionNames = new LinkedHashSet<>();
        for (String changedProjectName : changedProjectNames)
        {
            IProject changedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(changedProjectName);
            if (ContextLinks.isExtensionProject(changedProject))
            {
                changedExtensionNames.add(changedProjectName);
                changedProjects.add(changedProject);
            }
        }

        if (changedExtensionNames.isEmpty())
            return new RefreshPlan(changedProjects, dependentProjects);

        Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(ContextLinks::isExtensionProject)
            .filter(project -> !changedExtensionNames.contains(project.getName()))
            .filter(project -> dependsOnAny(project, changedExtensionNames))
            .forEach(dependentProjects::add);
        return new RefreshPlan(changedProjects, dependentProjects);
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

    private static void touchProjectSource(IProject project, IProgressMonitor monitor)
    {
        IResource touchedResource = findTouchResource(project);
        if (touchedResource == null)
            return;

        try
        {
            syntheticDeltaProjects.put(project.getName(), Long.valueOf(System.currentTimeMillis()));
            touchedResource.touch(monitor);
            ContextLinks.logWarning("EDT Context Links dependency refresh touched project=" + project.getName() //$NON-NLS-1$
                + " resource=" + touchedResource.getProjectRelativePath()); //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            ContextLinks.logError("EDT Context Links dependency refresh cannot touch " + project.getName(), e); //$NON-NLS-1$
        }
    }

    private static IResource findTouchResource(IProject project)
    {
        IResource src = project.findMember("src"); //$NON-NLS-1$
        if (src == null || !src.exists())
            return null;

        IResource[] result = new IResource[1];
        try
        {
            src.accept((IResourceVisitor)resource ->
            {
                if (result[0] != null)
                    return false;

                if (resource.getType() == IResource.FILE)
                {
                    String extension = resource.getFileExtension();
                    if ("bsl".equalsIgnoreCase(extension) || "mdo".equalsIgnoreCase(extension)) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        result[0] = resource;
                        return false;
                    }
                }
                return true;
            });
        }
        catch (CoreException e)
        {
            ContextLinks.logError("EDT Context Links dependency refresh cannot find touch resource for " //$NON-NLS-1$
                + project.getName(), e);
        }
        return result[0] != null ? result[0] : src;
    }

    private static boolean isSyntheticDelta(String projectName)
    {
        Long timestamp = syntheticDeltaProjects.get(projectName);
        if (timestamp == null)
            return false;

        long age = System.currentTimeMillis() - timestamp.longValue();
        if (age <= SYNTHETIC_DELTA_SUPPRESS_MS)
            return true;

        syntheticDeltaProjects.remove(projectName);
        return false;
    }

    private static String describeProjects(Set<IProject> projects)
    {
        return projects.stream()
            .map(IProject::getName)
            .toList()
            .toString();
    }

    private record RefreshPlan(Set<IProject> changedProjects, Set<IProject> dependentProjects)
    {
        boolean isEmpty()
        {
            return changedProjects.isEmpty() && dependentProjects.isEmpty();
        }

        int size()
        {
            return changedProjects.size() + dependentProjects.size();
        }
    }
}
