package ru.xelgo.edt.contextlinks.core;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import org.eclipse.equinox.service.weaving.IWeavingServiceFactory;

/**
 * Starts the plugin early enough for OSGi-level services.
 */
public final class ContextLinksPlugin
    extends Plugin
{
    private ServiceRegistration<IWeavingServiceFactory> queryWizardWeavingRegistration;

    @Override
    public void start(BundleContext context)
        throws Exception
    {
        super.start(context);
        if (ContextLinksPreferences.isQueryWizardEnabled())
        {
            queryWizardWeavingRegistration =
                context.registerService(IWeavingServiceFactory.class, new ContextLinksQueryWizardWeavingServiceFactory(),
                    null);
            ContextLinks.logDebug("EDT Extension Tweaks Query Wizard weaving service registered"); //$NON-NLS-1$
        }
        else
        {
            ContextLinks.logDebug("EDT Extension Tweaks Query Wizard weaving service disabled by preferences"); //$NON-NLS-1$
        }
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
        ContextLinksInfobaseSynchronizationManagerRegistrar.ensureRegistered();
    }

    @Override
    public void stop(BundleContext context)
        throws Exception
    {
        ContextLinksInfobaseSynchronizationManagerRegistrar.unregister();
        if (queryWizardWeavingRegistration != null)
        {
            queryWizardWeavingRegistration.unregister();
            queryWizardWeavingRegistration = null;
        }
        super.stop(context);
    }
}
