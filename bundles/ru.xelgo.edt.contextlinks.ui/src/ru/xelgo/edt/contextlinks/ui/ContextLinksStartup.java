package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.ui.IStartup;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksV8GlobalScopeProviderRegistrar;

/**
 * Registers scope wrappers after EDT opens the workspace.
 */
public class ContextLinksStartup
    implements IStartup
{
    @Override
    public void earlyStartup()
    {
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
        ContextLinksWorkbenchViewActivationService.start();
        ContextLinks.logDebug("EDT Extension Tweaks startup warm-up builds are disabled"); //$NON-NLS-1$
    }
}
