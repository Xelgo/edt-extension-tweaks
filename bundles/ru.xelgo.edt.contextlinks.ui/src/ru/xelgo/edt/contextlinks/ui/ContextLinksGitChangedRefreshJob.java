package ru.xelgo.edt.contextlinks.ui;

import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.ui.ContextLinksGitChangedResources.Snapshot;

/**
 * Serializes background scans and rejects results superseded by a newer request or a closed viewer.
 */
final class ContextLinksGitChangedRefreshJob extends Job implements AutoCloseable
{
    @FunctionalInterface
    interface Loader
    {
        Snapshot read(IProgressMonitor monitor);
    }

    private final Loader loader;
    private final Consumer<Runnable> uiExecutor;
    private final Consumer<Snapshot> publish;
    private final Runnable canceled;
    private long revision;
    private boolean closed;

    ContextLinksGitChangedRefreshJob(Loader loader, Consumer<Runnable> uiExecutor, Consumer<Snapshot> publish,
        Runnable canceled)
    {
        super("Расчёт изменённых Git-объектов"); //$NON-NLS-1$
        this.loader = loader;
        this.uiExecutor = uiExecutor;
        this.publish = publish;
        this.canceled = canceled;
        setPriority(Job.DECORATE);
        setUser(true);
    }

    synchronized void request(long delay)
    {
        if (closed)
            return;
        revision++;
        // Eclipse reschedules a running job after it finishes, so scans never overlap.
        schedule(delay);
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        revision++;
        cancel();
    }

    @Override
    protected IStatus run(IProgressMonitor monitor)
    {
        long requestedRevision;
        synchronized (this)
        {
            if (closed)
                return Status.CANCEL_STATUS;
            requestedRevision = revision;
        }
        monitor.beginTask(getName(), IProgressMonitor.UNKNOWN);
        try
        {
            Snapshot snapshot = loader.read(monitor);
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            deliver(requestedRevision, () -> publish.accept(snapshot));
            return Status.OK_STATUS;
        }
        catch (OperationCanceledException e)
        {
            deliver(requestedRevision, canceled);
            return Status.CANCEL_STATUS;
        }
        catch (RuntimeException e)
        {
            deliver(requestedRevision, canceled);
            return new Status(IStatus.ERROR, ContextLinks.PLUGIN_ID, "Failed to calculate Git changed objects", e); //$NON-NLS-1$
        }
        finally
        {
            monitor.done();
        }
    }

    private void deliver(long requestedRevision, Runnable action)
    {
        uiExecutor.accept(() -> {
            synchronized (this)
            {
                if (closed || revision != requestedRevision)
                    return;
            }
            action.run();
        });
    }
}
