package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.wiring.ServiceAccess;

import ru.xelgo.edt.contextlinks.core.AdoptedFormReload;
import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksPreferences;

/**
 * Extends the successful native "update adopted form" command without replacing its handler.
 */
public final class AdoptedFormReloadListener implements IExecutionListener
{
    static final String COMMAND = "com._1c.g5.v8.dt.form.ui.extension.commands.updateAdoptedForm"; //$NON-NLS-1$
    private static final AdoptedFormReloadListener INSTANCE = new AdoptedFormReloadListener();

    private ICommandService commands;
    private Request pending;
    private boolean reloading;
    private boolean unsupportedLogged;
    private volatile boolean stopped;

    private record Request(IEditorPart editor, IFile file, IDtProject project) { }

    public static void start()
    {
        INSTANCE.stopped = false;
        if (PlatformUI.isWorkbenchRunning())
            PlatformUI.getWorkbench().getDisplay().asyncExec(INSTANCE::install);
    }

    private synchronized void install()
    {
        if (!stopped && commands == null && PlatformUI.isWorkbenchRunning())
        {
            commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
            {
                commands.addExecutionListener(this);
                ContextLinks.logInfo("EDT Extension Tweaks adopted form BM reload listener registered"); //$NON-NLS-1$
            }
        }
    }

    public static void stop()
    {
        synchronized (INSTANCE)
        {
            INSTANCE.stopped = true;
            if (INSTANCE.commands != null)
            {
                INSTANCE.commands.removeExecutionListener(INSTANCE);
                INSTANCE.commands = null;
            }
            INSTANCE.pending = null;
        }
    }

    @Override
    public void preExecute(String commandId, ExecutionEvent event)
    {
        if (!COMMAND.equals(commandId) || reloading || stopped)
            return;
        pending = null;
        if (!ContextLinksPreferences.isAdoptedFormReloadEnabled())
            return;
        if (!AdoptedFormReload.isSupported())
        {
            if (!unsupportedLogged)
            {
                unsupportedLogged = true;
                ContextLinks.logInfo("EDT Extension Tweaks adopted form BM reload unavailable: " //$NON-NLS-1$
                    + "this EDT version does not expose IResourceStoreManager.forceImport"); //$NON-NLS-1$
            }
            return;
        }

        try
        {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (editor == null || !"com._1c.g5.v8.dt.form.ui.formEditor".equals(editor.getSite().getId())) //$NON-NLS-1$
                return;
            // The plugin does not require an internal EDT form UI bundle.
            Object value = editor.getClass().getMethod("getForm").invoke(editor); //$NON-NLS-1$
            IResourceLookup lookup = ServiceAccess.get(IResourceLookup.class);
            if (!(value instanceof EObject form) || lookup == null)
                return;
            IFile file = lookup.getPlatformResource(form);
            IDtProject project = lookup.getDtProject(form);
            if (file != null && project != null && ContextLinks.isExtensionProject(file.getProject()))
                pending = new Request(editor, file, project);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            ContextLinks.logError("EDT Extension Tweaks cannot locate the adopted form to reload", e); //$NON-NLS-1$
        }
    }

    @Override
    public void postExecuteSuccess(String commandId, Object returnValue)
    {
        if (!COMMAND.equals(commandId))
            return;
        Request request = pending;
        pending = null;
        // The native handler returns false if saving was canceled or no base form was found.
        if (request == null || !Boolean.TRUE.equals(returnValue) || reloading)
            return;

        reloading = true;
        try
        {
            if (request.editor().isDirty())
                request.editor().doSave(new NullProgressMonitor());
            if (request.editor().isDirty())
                throw new IllegalStateException("The form editor could not save its changes"); //$NON-NLS-1$

            IBmModelManager models = ServiceAccess.get(IBmModelManager.class);
            IResourceStoreManager resources = ServiceAccess.get(IResourceStoreManager.class);
            ContextLinks.logInfo("EDT Extension Tweaks requesting adopted form BM reload: " //$NON-NLS-1$
                + request.file().getFullPath());
            new ProgressMonitorDialog(request.editor().getSite().getShell()).run(true, false, monitor -> {
                monitor.beginTask(Messages.AdoptedFormReload_Progress, -1);
                try
                {
                    AdoptedFormReload.reload(request.file(), request.project(), models, resources, monitor);
                }
                catch (Exception e)
                {
                    throw new InvocationTargetException(e);
                }
                finally
                {
                    monitor.done();
                }
            });
            ContextLinks.logInfo("EDT Extension Tweaks adopted form BM reload synchronization finished: " //$NON-NLS-1$
                + request.file().getFullPath());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (InvocationTargetException | RuntimeException e)
        {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            if (!(cause instanceof OperationCanceledException))
            {
                StatusManager.getManager().handle(new Status(IStatus.ERROR, ContextLinks.PLUGIN_ID,
                    Messages.AdoptedFormReload_Error, cause), StatusManager.SHOW | StatusManager.LOG);
            }
        }
        finally
        {
            reloading = false;
        }
    }

    @Override
    public void postExecuteFailure(String commandId, ExecutionException exception)
    {
        if (COMMAND.equals(commandId))
            pending = null;
    }

    @Override
    public void notHandled(String commandId, NotHandledException exception)
    {
        if (COMMAND.equals(commandId))
            pending = null;
    }
}
