package ru.xelgo.edt.contextlinks.core;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

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
