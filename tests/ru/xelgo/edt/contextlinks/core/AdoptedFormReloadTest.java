package ru.xelgo.edt.contextlinks.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;

public class AdoptedFormReloadTest
{
    private final List<String> calls = new ArrayList<>();
    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private final IProject workspace = proxy(IProject.class, (name, args) -> null);
    private boolean baseExists = true;
    private boolean formExists = true;
    private boolean cancelOnWait;
    private boolean failImport;
    private final IFile base = file("base");
    private final IFile form = file("form");
    private final IDtProject project = proxy(IDtProject.class, (name, args) -> {
        if ("getWorkspaceProject".equals(name))
            return workspace;
        throw new AssertionError(name);
    });
    private final IBmModelManager models = proxy(IBmModelManager.class, (name, args) -> {
        if ("waitModelSynchronization".equals(name))
        {
            assertEquals(workspace, args[0]);
            calls.add("wait");
            if (cancelOnWait)
                monitor.setCanceled(true);
            return null;
        }
        throw new AssertionError(name);
    });
    private final IResourceStoreManager resources = proxy(IResourceStoreManager.class, (name, args) -> {
        if ("forceImport".equals(name))
        {
            assertEquals(project, args[1]);
            calls.add(args[0] == base ? "import base" : "import form");
            if (failImport)
                throw new IllegalStateException("Import failed");
            return null;
        }
        throw new AssertionError(name);
    });

    @Test
    public void importsSavedBaseBeforeFormAndWaitsBetweenStages() throws Exception
    {
        AdoptedFormReload.reload(form, project, models, resources, monitor);
        assertEquals(List.of("wait", "refresh base", "import base", "wait",
            "refresh form", "import form", "wait"), calls);
    }

    @Test
    public void reloadsFormWithoutBaseSnapshot() throws Exception
    {
        baseExists = false;
        AdoptedFormReload.reload(form, project, models, resources, monitor);
        assertEquals(List.of("wait", "refresh form", "import form", "wait"), calls);
    }

    @Test
    public void cancellationBeforeReloadDoesNotImport()
    {
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class,
            () -> AdoptedFormReload.reload(form, project, models, resources, monitor));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void cancellationWhileWaitingDoesNotImportOldDiskSnapshot()
    {
        cancelOnWait = true;
        assertThrows(OperationCanceledException.class,
            () -> AdoptedFormReload.reload(form, project, models, resources, monitor));
        assertEquals(List.of("wait"), calls);
    }

    @Test
    public void missingSourceDoesNotImport()
    {
        formExists = false;
        assertThrows(IllegalArgumentException.class,
            () -> AdoptedFormReload.reload(form, project, models, resources, monitor));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void rejectsSourceFromAnotherProject()
    {
        IDtProject other = proxy(IDtProject.class, (name, args) -> proxy(IProject.class, (n, a) -> null));
        assertThrows(IllegalArgumentException.class,
            () -> AdoptedFormReload.reload(form, other, models, resources, monitor));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void doesNotReportSuccessIfImportFails()
    {
        failImport = true;
        assertThrows(IllegalStateException.class,
            () -> AdoptedFormReload.reload(form, project, models, resources, monitor));
        assertEquals(List.of("wait", "refresh base", "import base"), calls);
    }

    @Test
    public void requiresNativeImportService()
    {
        assertThrows(IllegalStateException.class,
            () -> AdoptedFormReload.reload(form, project, models, null, monitor));
        assertTrue(calls.isEmpty());
    }

    private IFile file(String label)
    {
        return proxy(IFile.class, (name, args) -> {
            switch (name)
            {
                case "getName": return "Form.form";
                case "getProject": return workspace;
                case "isAccessible": return "base".equals(label) ? baseExists : formExists;
                case "getParent": return proxy(IContainer.class, (n, a) -> {
                    assertEquals("getFile", n);
                    assertEquals("BaseForm/Form.form", a[0].toString());
                    return base;
                });
                case "refreshLocal":
                    calls.add("refresh " + label);
                    return null;
                default:
                    // In particular, no create/setContents/delete/touch operations are permitted.
                    throw new AssertionError("Unexpected file operation: " + name);
            }
        });
    }

    @FunctionalInterface
    private interface Call
    {
        Object invoke(String name, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Call call)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            if ("equals".equals(method.getName()))
                return object == args[0];
            if ("hashCode".equals(method.getName()))
                return System.identityHashCode(object);
            if ("toString".equals(method.getName()))
                return type.getSimpleName();
            return call.invoke(method.getName(), args);
        }));
    }
}
