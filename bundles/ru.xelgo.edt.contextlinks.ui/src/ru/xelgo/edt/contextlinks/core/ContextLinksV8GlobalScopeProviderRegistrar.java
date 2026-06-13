package ru.xelgo.edt.contextlinks.core;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * Registers a narrow QL-aware wrapper around EDT's global BM scope provider.
 */
public final class ContextLinksV8GlobalScopeProviderRegistrar
{
    private static final String DISABLE_PROPERTY = ContextLinks.PLUGIN_ID + ".qlV8Scope.disable"; //$NON-NLS-1$
    private static ServiceRegistration<?> registration;

    private ContextLinksV8GlobalScopeProviderRegistrar()
    {
        // Utility class.
    }

    public static synchronized void ensureRegistered()
    {
        if (registration != null || Boolean.getBoolean(DISABLE_PROPERTY))
            return;

        Bundle bundle = FrameworkUtil.getBundle(ContextLinksV8GlobalScopeProviderRegistrar.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context == null)
            return;

        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, Integer.valueOf(1000));
        properties.put(ContextLinksV8GlobalScopeProviderProxy.WRAPPER_MARKER_PROPERTY, Boolean.TRUE.toString());

        Object proxy = ContextLinksV8GlobalScopeProviderProxy.create(context);
        if (proxy == null)
            return;

        registration = context.registerService(ContextLinksV8GlobalScopeProviderProxy.SERVICE_CLASS_NAME, proxy, properties);
        ContextLinks.logWarning("EDT Context Links QL BM global scope wrapper registered"); //$NON-NLS-1$
    }
}
