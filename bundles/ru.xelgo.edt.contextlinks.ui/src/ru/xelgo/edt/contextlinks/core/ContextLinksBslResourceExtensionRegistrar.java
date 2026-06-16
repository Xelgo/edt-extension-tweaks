package ru.xelgo.edt.contextlinks.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com._1c.g5.v8.dt.core.resource.extension.IBslResourceExtension;
import com._1c.g5.v8.dt.core.resource.extension.IBslResourceExtensionManager;
import com._1c.g5.wiring.ServiceAccess;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Registers dirty preprocessor normalization in EDT's BSL resource extension hook.
 */
public final class ContextLinksBslResourceExtensionRegistrar
{
    private static final String DISABLE_PROPERTY = ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.disable"; //$NON-NLS-1$
    private static final int MAX_RETRY_ATTEMPTS = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.registrationRetryAttempts", 30); //$NON-NLS-1$
    private static final long RETRY_DELAY_MILLIS = Long.getLong(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.registrationRetryDelayMillis", 1000L); //$NON-NLS-1$
    private static final Set<String> loggedRegistrationStates = ConcurrentHashMap.newKeySet();
    private static final IBslResourceExtension EXTENSION = new ContextLinksBslResourceExtension();
    private static final AtomicBoolean retryScheduled = new AtomicBoolean();
    private static volatile boolean registered;
    private static volatile boolean blockedByAnotherExtension;

    private ContextLinksBslResourceExtensionRegistrar()
    {
        // Utility class.
    }

    public static synchronized void ensureRegistered()
    {
        if (registered || blockedByAnotherExtension)
            return;

        if (Boolean.getBoolean(DISABLE_PROPERTY))
        {
            logRegistrationState("EDT Extension Tweaks dirty preprocessor normalization disabled by system property"); //$NON-NLS-1$
            return;
        }

        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
        {
            logRegistrationState("EDT Extension Tweaks dirty preprocessor normalization disabled by preferences"); //$NON-NLS-1$
            return;
        }

        RegistrationState state = tryRegister();
        if (state == RegistrationState.UNAVAILABLE)
            scheduleRetry();
    }

    private static RegistrationState tryRegister()
    {
        IBslResourceExtensionManager manager;
        try
        {
            manager = ServiceAccess.get(IBslResourceExtensionManager.class);
        }
        catch (RuntimeException e)
        {
            logRegistrationState(
                "EDT Extension Tweaks dirty preprocessor normalization not registered yet: manager unavailable (" //$NON-NLS-1$
                    + e.getClass().getSimpleName() + ")");
            return RegistrationState.UNAVAILABLE;
        }

        if (manager == null)
        {
            logRegistrationState("EDT Extension Tweaks dirty preprocessor normalization not registered: manager is null"); //$NON-NLS-1$
            return RegistrationState.UNAVAILABLE;
        }

        IBslResourceExtension currentExtension = manager.getResourceExtension();
        if (currentExtension == EXTENSION)
        {
            registered = true;
            return RegistrationState.REGISTERED;
        }

        if (currentExtension != null)
        {
            blockedByAnotherExtension = true;
            logRegistrationState("EDT Extension Tweaks dirty preprocessor normalization not registered: manager already has " //$NON-NLS-1$
                + currentExtension.getClass().getName());
            return RegistrationState.BLOCKED;
        }

        try
        {
            manager.setBslResourceExtension(EXTENSION);
        }
        catch (RuntimeException e)
        {
            logRegistrationState(
                "EDT Extension Tweaks dirty preprocessor normalization registration failed (" //$NON-NLS-1$
                    + e.getClass().getSimpleName() + ")");
            return RegistrationState.UNAVAILABLE;
        }

        if (manager.getResourceExtension() == EXTENSION)
        {
            registered = true;
            logRegistrationState("EDT Extension Tweaks dirty preprocessor normalization registered"); //$NON-NLS-1$
            return RegistrationState.REGISTERED;
        }

        logRegistrationState("EDT Extension Tweaks dirty preprocessor normalization registration was ignored"); //$NON-NLS-1$
        return RegistrationState.UNAVAILABLE;
    }

    private static void scheduleRetry()
    {
        if (!retryScheduled.compareAndSet(false, true))
            return;

        Job job = new Job("EDT Extension Tweaks dirty preprocessor registration") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS && !monitor.isCanceled(); attempt++)
                    {
                        synchronized (ContextLinksBslResourceExtensionRegistrar.class)
                        {
                            if (registered || blockedByAnotherExtension)
                                return Status.OK_STATUS;

                            RegistrationState state = tryRegister();
                            if (state == RegistrationState.REGISTERED || state == RegistrationState.BLOCKED)
                                return Status.OK_STATUS;
                        }

                        Thread.sleep(RETRY_DELAY_MILLIS);
                    }

                    logRegistrationState(
                        "EDT Extension Tweaks dirty preprocessor normalization unavailable after retry attempts=" //$NON-NLS-1$
                            + MAX_RETRY_ATTEMPTS);
                    return Status.OK_STATUS;
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                }
                finally
                {
                    retryScheduled.set(false);
                }
            }
        };
        job.setSystem(true);
        job.schedule(RETRY_DELAY_MILLIS);
    }

    private static void logRegistrationState(String message)
    {
        if (loggedRegistrationStates.add(message))
            ContextLinks.logDebug(message);
    }

    private enum RegistrationState
    {
        REGISTERED,
        BLOCKED,
        UNAVAILABLE
    }
}
