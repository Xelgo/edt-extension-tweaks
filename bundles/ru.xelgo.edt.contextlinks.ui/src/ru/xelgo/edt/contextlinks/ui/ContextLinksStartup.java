package ru.xelgo.edt.contextlinks.ui;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksV8GlobalScopeProviderRegistrar;

/**
 * Warms linked extension projects after EDT opens the workspace.
 */
public class ContextLinksStartup
    implements IStartup
{
    private static final long[] WARMUP_DELAYS_MS = { 15_000L, 45_000L };

    @Override
    public void earlyStartup()
    {
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
        ContextLinks.logWarning("EDT Context Links startup warm-up scheduled"); //$NON-NLS-1$
        for (int i = 0; i < WARMUP_DELAYS_MS.length; i++)
            new WarmupJob(i + 1).schedule(WARMUP_DELAYS_MS[i]);
    }

    private static final class WarmupJob
        extends WorkspaceJob
    {
        private final int pass;

        WarmupJob(int pass)
        {
            super("EDT Context Links startup warm-up " + pass); //$NON-NLS-1$
            this.pass = pass;
            setRule(ResourcesPlugin.getWorkspace().getRoot());
            setSystem(true);
        }

        @Override
        public IStatus runInWorkspace(IProgressMonitor monitor)
        {
            Set<IProject> projectsToBuild = collectProjectsToBuild();
            ContextLinks.logWarning("EDT Context Links startup warm-up pass=" + pass //$NON-NLS-1$
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
            return Status.OK_STATUS;
        }

        private Set<IProject> collectProjectsToBuild()
        {
            Set<IProject> result = new LinkedHashSet<>();
            IProject[] workspaceProjects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : workspaceProjects)
            {
                if (!ContextLinks.isExtensionProject(project))
                    continue;

                Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
                if (linkedProjectNames.isEmpty())
                    continue;

                for (String linkedProjectName : linkedProjectNames)
                {
                    IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
                    if (ContextLinks.isExtensionProject(linkedProject))
                        result.add(linkedProject);
                }
                result.add(project);
            }
            return result;
        }

        private void build(IProject project, IProgressMonitor monitor)
        {
            try
            {
                ContextLinks.logWarning("EDT Context Links startup warm-up build pass=" + pass //$NON-NLS-1$
                    + " project=" + project.getName()); //$NON-NLS-1$
                project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
            }
            catch (CoreException e)
            {
                ContextLinks.logError("EDT Context Links startup warm-up failed for " + project.getName(), e); //$NON-NLS-1$
            }
        }

        private String describeProjects(Set<IProject> projects)
        {
            return projects.stream()
                .map(IProject::getName)
                .toList()
                .toString();
        }
    }
}
