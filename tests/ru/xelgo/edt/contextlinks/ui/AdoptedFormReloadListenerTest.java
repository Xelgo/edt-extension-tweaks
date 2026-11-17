package ru.xelgo.edt.contextlinks.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import org.eclipse.ui.IEditorPart;
import org.eclipse.core.resources.IFile;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;

public class AdoptedFormReloadListenerTest
{
    @Test
    public void canceledNativeUpdateClearsRequestWithoutReloading() throws Exception
    {
        AdoptedFormReloadListener listener = pendingListener();
        // Null editor/file would fail if the reload path were entered.
        listener.postExecuteSuccess(AdoptedFormReloadListener.COMMAND, Boolean.FALSE);
        assertNull(pending().get(listener));
    }

    @Test
    public void failedNativeUpdateClearsRequest() throws Exception
    {
        AdoptedFormReloadListener listener = pendingListener();
        listener.postExecuteFailure(AdoptedFormReloadListener.COMMAND, null);
        assertNull(pending().get(listener));
    }

    @Test
    public void unhandledNativeUpdateClearsRequest() throws Exception
    {
        AdoptedFormReloadListener listener = pendingListener();
        listener.notHandled(AdoptedFormReloadListener.COMMAND, null);
        assertNull(pending().get(listener));
    }

    @Test
    public void nestedUnrelatedCommandDoesNotConsumeFormRequest() throws Exception
    {
        AdoptedFormReloadListener listener = pendingListener();
        listener.postExecuteSuccess("org.eclipse.ui.file.save", Boolean.TRUE);
        listener.postExecuteFailure("org.eclipse.ui.file.save", null);
        listener.notHandled("org.eclipse.ui.file.save", null);
        assertNotNull(pending().get(listener));
    }

    private static AdoptedFormReloadListener pendingListener() throws Exception
    {
        AdoptedFormReloadListener listener = new AdoptedFormReloadListener();
        Class<?> request = Class.forName(AdoptedFormReloadListener.class.getName() + "$Request");
        Constructor<?> constructor = request.getDeclaredConstructor(IEditorPart.class, IFile.class, IDtProject.class);
        constructor.setAccessible(true);
        pending().set(listener, constructor.newInstance(null, null, null));
        return listener;
    }

    private static Field pending() throws Exception
    {
        Field field = AdoptedFormReloadListener.class.getDeclaredField("pending");
        field.setAccessible(true);
        return field;
    }
}
