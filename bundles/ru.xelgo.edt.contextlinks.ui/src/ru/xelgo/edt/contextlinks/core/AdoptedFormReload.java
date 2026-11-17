package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;

/**
 * Reimports a saved adopted form through EDT's resource synchronization pipeline.
 * Must run outside the UI thread, BM transactions and workspace scheduling rules.
 */
public final class AdoptedFormReload
{
    private AdoptedFormReload()
    {
        // Utility class.
    }

    public static boolean isSupported()
    {
        try
        {
            forceImportMethod();
            return true;
        }
        catch (NoSuchMethodException e)
        {
            return false;
        }
    }

    public static void reload(IFile form, IDtProject project, IBmModelManager models,
        IResourceStoreManager resources, IProgressMonitor monitor) throws CoreException
    {
        if (form == null || !"Form.form".equals(form.getName()) || !form.isAccessible() //$NON-NLS-1$
            || project == null || !form.getProject().equals(project.getWorkspaceProject()))
        {
            throw new IllegalArgumentException("Expected a saved Form.form in the extension project"); //$NON-NLS-1$
        }
        if (models == null || resources == null)
            throw new IllegalStateException("EDT form import services are unavailable"); //$NON-NLS-1$

        checkCanceled(monitor);
        // Adoption can still be exporting its BM changes. Do not import an older disk snapshot.
        models.waitModelSynchronization(form.getProject());
        checkCanceled(monitor);

        IFile base = form.getParent().getFile(new Path("BaseForm/Form.form")); //$NON-NLS-1$
        if (base.isAccessible())
            importFile(base, project, models, resources, monitor);
        importFile(form, project, models, resources, monitor);
    }

    private static void importFile(IFile file, IDtProject project, IBmModelManager models,
        IResourceStoreManager resources, IProgressMonitor monitor) throws CoreException
    {
        checkCanceled(monitor);
        file.refreshLocal(0, monitor);
        checkCanceled(monitor);
        // A resource refresh/touch alone is insufficient when the source bytes have not changed.
        try
        {
            forceImportMethod().invoke(resources, file, project);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime)
                throw runtime;
            if (cause instanceof Error error)
                throw error;
            throw new IllegalStateException("EDT forceImport failed", cause); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("This EDT version does not expose forceImport", e); //$NON-NLS-1$
        }
        models.waitModelSynchronization(file.getProject());
        checkCanceled(monitor);
    }

    private static Method forceImportMethod() throws NoSuchMethodException
    {
        // Introduced after EDT 2025.2. Keep the plugin loadable on its older supported targets.
        return IResourceStoreManager.class.getMethod("forceImport", IFile.class, IDtProject.class); //$NON-NLS-1$
    }

    private static void checkCanceled(IProgressMonitor monitor)
    {
        if (monitor.isCanceled())
            throw new OperationCanceledException();
    }
}
