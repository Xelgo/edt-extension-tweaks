package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.validation.marker.Marker;

import ru.xelgo.edt.contextlinks.core.ChangeAndValidateBatchApplier;
import ru.xelgo.edt.contextlinks.core.ChangeAndValidateBatchCollector;
import ru.xelgo.edt.contextlinks.core.ChangeAndValidateBatchItem;
import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Opens one merge window for every diverged ChangeAndValidate method.
 *
 * <p>Invoked from the problem view (works on the marker selection) and from the navigator (works on
 * the selected extension projects).
 */
public class ChangeAndValidateBatchMergeHandler
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
        throws ExecutionException
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        Shell shell = window != null ? window.getShell() : null;
        IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);

        // The problem view mixes markers of every check. A selection that holds markers but none of
        // ours is not a reason to fall back to scanning the whole workspace.
        if (hasMarkers(selection))
        {
            List<Marker> selected = markersOf(selection);
            if (selected.isEmpty())
            {
                MessageDialog.openInformation(shell, Messages.ChangeAndValidateBatch_DialogTitle,
                    Messages.ChangeAndValidateBatch_NoSuitableMarkers);
                return null;
            }
            return openFor(shell, window, selected, List.of());
        }

        return openFor(shell, window, List.of(), projectsOf(selection));
    }

    private Object openFor(Shell shell, IWorkbenchWindow window, List<Marker> markers, List<IProject> projects)
    {
        List<ChangeAndValidateBatchItem> items;
        try
        {
            items = collect(shell, markers, projects);
        }
        catch (OperationCanceledException | InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (InvocationTargetException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: collection failed", e.getCause()); //$NON-NLS-1$
            MessageDialog.openError(shell, Messages.ChangeAndValidateBatch_DialogTitle,
                Messages.ChangeAndValidateBatch_CollectFailed);
            return null;
        }

        if (items.isEmpty())
        {
            MessageDialog.openInformation(shell, Messages.ChangeAndValidateBatch_DialogTitle,
                Messages.ChangeAndValidateBatch_NothingFound);
            return null;
        }

        ChangeAndValidateBatchMergeDialog dialog =
            new ChangeAndValidateBatchMergeDialog(shell, items, window != null ? window : PlatformUI.getWorkbench());
        if (dialog.open() != Window.OK)
            return null;

        applyWithProgress(shell, items);
        return null;
    }

    private static List<ChangeAndValidateBatchItem> collect(Shell shell, List<Marker> markers, List<IProject> projects)
        throws InvocationTargetException, InterruptedException
    {
        List<ChangeAndValidateBatchItem> result = new ArrayList<>();
        // Collection runs off the UI thread: it reads modules and must not block the workbench.
        IRunnableWithProgress runnable = monitor -> {
            monitor.beginTask(Messages.ChangeAndValidateBatch_CollectTask, IProgressMonitor.UNKNOWN);
            try
            {
                if (!markers.isEmpty())
                    result.addAll(ChangeAndValidateBatchCollector.collectFromMarkers(markers, monitor));
                else
                    result.addAll(ChangeAndValidateBatchCollector.collect(projects, monitor));
            }
            finally
            {
                monitor.done();
            }
        };
        new ProgressMonitorDialog(shell).run(true, true, runnable);
        return result;
    }

    private static void applyWithProgress(Shell shell, List<ChangeAndValidateBatchItem> items)
    {
        ChangeAndValidateBatchApplier.Report[] report = new ChangeAndValidateBatchApplier.Report[1];
        try
        {
            new ProgressMonitorDialog(shell).run(true, false, monitor -> {
                monitor.beginTask(Messages.ChangeAndValidateBatch_ApplyTask, IProgressMonitor.UNKNOWN);
                try
                {
                    report[0] = ChangeAndValidateBatchApplier.apply(items, monitor);
                }
                finally
                {
                    monitor.done();
                }
            });
        }
        catch (InvocationTargetException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: apply failed", e.getCause()); //$NON-NLS-1$
            MessageDialog.openError(shell, Messages.ChangeAndValidateBatch_DialogTitle, e.getCause().getMessage());
            return;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return;
        }

        showReport(shell, report[0]);
    }

    private static void showReport(Shell shell, ChangeAndValidateBatchApplier.Report report)
    {
        if (report == null)
            return;

        String message = String.format(Messages.ChangeAndValidateBatch_ReportApplied, report.getApplied().size());
        if (!report.hasSkipped())
        {
            MessageDialog.openInformation(shell, Messages.ChangeAndValidateBatch_DialogTitle, message);
            return;
        }

        StringBuilder text = new StringBuilder(message);
        text.append("\n\n").append(String.format(Messages.ChangeAndValidateBatch_ReportSkipped, //$NON-NLS-1$
            report.getSkipped().size()));
        for (String skipped : report.getSkipped())
            text.append("\n  - ").append(skipped); //$NON-NLS-1$
        MessageDialog.openWarning(shell, Messages.ChangeAndValidateBatch_DialogTitle, text.toString());
    }

    /** True when the selection came from the problem view, whatever the check codes are. */
    private static boolean hasMarkers(IStructuredSelection selection)
    {
        if (selection == null)
            return false;

        for (Object element : selection.toList())
        {
            if (adapt(element, Marker.class) != null)
                return true;
        }
        return false;
    }

    /** Problem view selection adapts to EDT markers; other check codes are filtered out. */
    private static List<Marker> markersOf(IStructuredSelection selection)
    {
        List<Marker> markers = new ArrayList<>();
        if (selection == null)
            return markers;

        for (Object element : selection.toList())
        {
            Marker marker = adapt(element, Marker.class);
            if (marker != null)
                markers.add(marker);
        }
        return ChangeAndValidateBatchCollector.filterSupported(markers);
    }

    private static List<IProject> projectsOf(IStructuredSelection selection)
    {
        Set<IProject> projects = new LinkedHashSet<>();
        if (selection != null)
        {
            for (Object element : selection.toList())
            {
                IProject project = adapt(element, IProject.class);
                if (project != null && project.isAccessible())
                    projects.add(project);
            }
        }
        if (projects.isEmpty())
            projects.addAll(Arrays.asList(ResourcesPlugin.getWorkspace().getRoot().getProjects()));
        return new ArrayList<>(projects);
    }

    private static <T> T adapt(Object element, Class<T> type)
    {
        if (type.isInstance(element))
            return type.cast(element);
        if (element instanceof IAdaptable adaptable)
            return adaptable.getAdapter(type);
        return null;
    }
}
