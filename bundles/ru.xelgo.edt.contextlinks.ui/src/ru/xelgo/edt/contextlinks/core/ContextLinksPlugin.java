package ru.xelgo.edt.contextlinks.core;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * Starts the plugin early enough for OSGi-level services.
 */
public final class ContextLinksPlugin
    extends Plugin
{
    @Override
    public void start(BundleContext context)
        throws Exception
    {
        super.start(context);
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
    }
}
