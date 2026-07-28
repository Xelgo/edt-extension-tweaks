package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.ui.IStartup;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksInvocationParameterInfoAutoActivation;
import ru.xelgo.edt.contextlinks.core.ContextLinksMethodCompletionCaretRelocator;
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
        ContextLinksInvocationParameterInfoAutoActivation.start();
        ContextLinksMethodCompletionCaretRelocator.start();
        ContextLinksActiveTabBoldFontService.start();
        ContextLinksWorkbenchViewActivationService.start();
        ContextLinks.logDebug("EDT Extension Tweaks startup warm-up builds are disabled"); //$NON-NLS-1$
    }
}
