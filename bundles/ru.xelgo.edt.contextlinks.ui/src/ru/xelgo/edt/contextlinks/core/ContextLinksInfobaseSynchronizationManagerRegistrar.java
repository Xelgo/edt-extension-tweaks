package ru.xelgo.edt.contextlinks.core;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;

/**
 * Registers a wrapper that can softly exclude selected EDT projects from infobase update flows.
 */
public final class ContextLinksInfobaseSynchronizationManagerRegistrar
{
    private static final String DISABLE_PROPERTY = ContextLinks.PLUGIN_ID + ".infobaseUpdateSkip.disable"; //$NON-NLS-1$
    static final String WRAPPER_MARKER_PROPERTY = ContextLinks.PLUGIN_ID + ".infobaseUpdateSkip.wrapper"; //$NON-NLS-1$
    private static final Set<String> loggedRegistrationStates = ConcurrentHashMap.newKeySet();
    private static ServiceRegistration<IInfobaseSynchronizationManager> registration;
    private static ServiceTracker<IInfobaseSynchronizationManager, IInfobaseSynchronizationManager> delegateTracker;

    private ContextLinksInfobaseSynchronizationManagerRegistrar()
    {
        // Utility class.
    }

    public static synchronized void ensureRegistered()
    {
        if (registration != null)
            return;

        if (Boolean.getBoolean(DISABLE_PROPERTY))
        {
            logRegistrationState("EDT Extension Tweaks infobase update skip wrapper disabled by system property"); //$NON-NLS-1$
            return;
        }

        Bundle bundle = FrameworkUtil.getBundle(ContextLinksInfobaseSynchronizationManagerRegistrar.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context == null)
        {
            logRegistrationState("EDT Extension Tweaks infobase update skip wrapper not registered: bundle context is null"); //$NON-NLS-1$
            return;
        }

        if (delegateTracker != null)
            return;

        try
        {
            Filter filter = context.createFilter("(&(" + Constants.OBJECTCLASS + "=" //$NON-NLS-1$ //$NON-NLS-2$
                + IInfobaseSynchronizationManager.class.getName() + ")(!(" + WRAPPER_MARKER_PROPERTY + "=*)))"); //$NON-NLS-1$ //$NON-NLS-2$
            delegateTracker =
                new ServiceTracker<IInfobaseSynchronizationManager, IInfobaseSynchronizationManager>(context, filter,
                    null)
                {
                    @Override
                    public IInfobaseSynchronizationManager addingService(
                        ServiceReference<IInfobaseSynchronizationManager> reference)
                    {
                        IInfobaseSynchronizationManager service = super.addingService(reference);
                        if (service != null)
                            registerProxy(context);
                        return service;
                    }
                };
            delegateTracker.open();
            logRegistrationState("EDT Extension Tweaks infobase update skip wrapper waiting for EDT delegate service"); //$NON-NLS-1$
        }
        catch (InvalidSyntaxException e)
        {
            ContextLinks.logError("EDT Extension Tweaks infobase update skip wrapper tracker failed", e); //$NON-NLS-1$
        }
    }

    public static synchronized void unregister()
    {
        if (registration != null)
        {
            registration.unregister();
            registration = null;
        }
        if (delegateTracker != null)
        {
            delegateTracker.close();
            delegateTracker = null;
        }
    }

    private static synchronized void registerProxy(BundleContext context)
    {
        if (registration != null)
            return;

        IInfobaseSynchronizationManager proxy = ContextLinksInfobaseSynchronizationManagerProxy.create(context);
        if (proxy == null)
        {
            logRegistrationState("EDT Extension Tweaks infobase update skip wrapper not registered: proxy creation failed"); //$NON-NLS-1$
            return;
        }

        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, Integer.valueOf(1000));
        properties.put(WRAPPER_MARKER_PROPERTY, Boolean.TRUE.toString());
        registration = context.registerService(IInfobaseSynchronizationManager.class, proxy, properties);
        ContextLinks.logWarning("EDT Extension Tweaks infobase update skip wrapper registered"); //$NON-NLS-1$
    }

    private static void logRegistrationState(String message)
    {
        if (loggedRegistrationStates.add(message))
            ContextLinks.logWarning(message);
    }
}
