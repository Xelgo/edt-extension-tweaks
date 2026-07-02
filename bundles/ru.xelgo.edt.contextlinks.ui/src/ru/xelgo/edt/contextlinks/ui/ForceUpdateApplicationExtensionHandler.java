package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksInfobaseSynchronizationServices;

/**
 * Forces a full update of one extension project into an infobase application.
 */
public class ForceUpdateApplicationExtensionHandler
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
        throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        IInfobaseApplication application = getSelectedApplication(event);
        if (application == null)
        {
            Messages.showInfo(shell, Messages.ForceUpdateApplicationExtensionHandler_Title,
                Messages.ForceUpdateApplicationExtensionHandler_NoApplication);
            return null;
        }

        IProject extensionProject = selectExtensionProject(shell, application);
        if (extensionProject == null)
            return null;

        IInfobaseUpdateCallback callback = getUpdateCallback(application);
        if (callback == null)
        {
            Messages.showError(shell, Messages.ForceUpdateApplicationExtensionHandler_Title,
                Messages.ForceUpdateApplicationExtensionHandler_NoCallback);
            return null;
        }

        scheduleUpdate(shell, application, extensionProject, callback);
        return null;
    }

    private IInfobaseApplication getSelectedApplication(ExecutionEvent event)
    {
        Object selected = HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection
            ? ((IStructuredSelection)HandlerUtil.getCurrentSelection(event)).getFirstElement()
            : null;
        return selected instanceof IInfobaseApplication ? (IInfobaseApplication)selected : null;
    }

    private IProject selectExtensionProject(Shell shell, IInfobaseApplication application)
    {
        IProject applicationProject = application.getProject();
        IProject[] extensions = Arrays.stream(ContextLinks.getApplicationUpdateCandidateProjects(applicationProject))
            .filter(project -> !project.equals(applicationProject))
            .filter(ContextLinks::isExtensionProject)
            .toArray(IProject[]::new);
        if (extensions.length == 0)
        {
            Messages.showInfo(shell, Messages.ForceUpdateApplicationExtensionHandler_Title,
                Messages.ForceUpdateApplicationExtensionHandler_NoExtensions);
            return null;
        }

        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof IProject ? ((IProject)element).getName() : super.getText(element);
            }
        });
        dialog.setElements(extensions);
        dialog.setMessage(Messages.ForceUpdateApplicationExtensionHandler_Message);
        dialog.setMultipleSelection(false);
        dialog.setTitle(Messages.ForceUpdateApplicationExtensionHandler_Title);

        return dialog.open() == Window.OK && dialog.getFirstResult() instanceof IProject
            ? (IProject)dialog.getFirstResult()
            : null;
    }

    private IInfobaseUpdateCallback getUpdateCallback(IInfobaseApplication application)
    {
        Object callback = Platform.getAdapterManager()
            .getAdapter(application.getInfobase(), IInfobaseUpdateCallback.class);
        return callback instanceof IInfobaseUpdateCallback ? (IInfobaseUpdateCallback)callback : null;
    }

    private void scheduleUpdate(Shell shell, IInfobaseApplication application, IProject extensionProject,
        IInfobaseUpdateCallback callback)
    {
        InfobaseReference infobase = application.getInfobase();
        Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : Display.getDefault();
        Job job = new Job(NLS.bind(Messages.ForceUpdateApplicationExtensionHandler_JobName,
            extensionProject.getName()))
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try (ContextLinksInfobaseSynchronizationServices.Lease lease =
                    ContextLinksInfobaseSynchronizationServices.acquireDelegate())
                {
                    if (lease == null)
                        return new Status(IStatus.ERROR, ContextLinks.PLUGIN_ID,
                            Messages.ForceUpdateApplicationExtensionHandler_NoSynchronizationManager);

                    ContextLinks.logInfo("EDT Extension Tweaks forcing extension reload: applicationProject=" //$NON-NLS-1$
                        + application.getProject().getName() + " extensionProject=" + extensionProject.getName()); //$NON-NLS-1$
                    return reloadInfobase(lease.service(), extensionProject, infobase, callback, monitor);
                }
                catch (Exception e)
                {
                    return new Status(IStatus.ERROR, ContextLinks.PLUGIN_ID,
                        Messages.ForceUpdateApplicationExtensionHandler_UpdateFailed, e);
                }
            }
        };
        job.setUser(true);
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                IStatus result = event.getResult();
                if (result == null || result.isOK() || result.matches(IStatus.CANCEL)
                    || display == null || display.isDisposed())
                    return;

                display.asyncExec(() -> Messages.showError(shell,
                    Messages.ForceUpdateApplicationExtensionHandler_Title,
                    result.getMessage()));
            }
        });
        job.schedule();
    }

    private IStatus reloadInfobase(IInfobaseSynchronizationManager manager, IProject extensionProject,
        InfobaseReference infobase, IInfobaseUpdateCallback callback, IProgressMonitor monitor)
        throws Exception
    {
        Method method = IInfobaseSynchronizationManager.class.getMethod("reloadInfobase", IProject.class, //$NON-NLS-1$
            InfobaseReference.class, IInfobaseUpdateCallback.class, Boolean.TYPE, IProgressMonitor.class);
        try
        {
            Object result = method.invoke(manager, extensionProject, infobase, callback, Boolean.FALSE, monitor);
            if (result instanceof IStatus)
                return (IStatus)result;
            if (result instanceof Boolean)
                return ((Boolean)result).booleanValue() ? Status.OK_STATUS : Status.CANCEL_STATUS;
            return Status.OK_STATUS;
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getTargetException();
            if (cause instanceof Exception)
                throw (Exception)cause;
            if (cause instanceof Error)
                throw (Error)cause;
            throw e;
        }
    }
}
