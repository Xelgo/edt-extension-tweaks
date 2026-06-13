package ru.xelgo.edt.contextlinks.core;

import org.eclipse.xtext.service.AbstractGenericModule;
import org.eclipse.xtext.resource.IContainer;
import org.eclipse.xtext.scoping.IScopeProvider;

import com._1c.g5.v8.dt.bsl.contextdef.IBslModuleContextDefService;
import com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider;

/**
 * Adds project context links to standard BSL scope services.
 */
public class ContextLinksBslRuntimeModule
    extends AbstractGenericModule
{
    public ContextLinksBslRuntimeModule()
    {
        ContextLinks.logDebug("EDT Context Links BSL runtime module constructed"); //$NON-NLS-1$
    }

    public Class<? extends BslCachedScopeProvider> bindBslCachedScopeProvider()
    {
        ContextLinks.logDebug("EDT Context Links BSL runtime module binding BslCachedScopeProvider -> " //$NON-NLS-1$
            + ContextLinksCachedScopeProvider.class.getName());
        return ContextLinksCachedScopeProvider.class;
    }

    public Class<? extends IScopeProvider> bindIScopeProvider()
    {
        ContextLinks.logDebug("EDT Context Links BSL runtime module binding IScopeProvider -> " //$NON-NLS-1$
            + ContextLinksBslScopeProvider.class.getName());
        return ContextLinksBslScopeProvider.class;
    }

    public Class<? extends IBslModuleContextDefService> bindIBslModuleContextDefService()
    {
        ContextLinks.logDebug("EDT Context Links BSL runtime module binding IBslModuleContextDefService -> " //$NON-NLS-1$
            + ContextLinksModuleContextDefService.class.getName());
        return ContextLinksModuleContextDefService.class;
    }

    public Class<? extends IContainer.Manager> bindIContainer$Manager()
    {
        ContextLinks.logDebug("EDT Context Links BSL runtime module binding IContainer.Manager -> " //$NON-NLS-1$
            + ContextLinksContainerManager.class.getName());
        return ContextLinksContainerManager.class;
    }
}
