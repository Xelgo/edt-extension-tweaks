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
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
    }

    public Class<? extends BslCachedScopeProvider> bindBslCachedScopeProvider()
    {
        return ContextLinksCachedScopeProvider.class;
    }

    public Class<? extends IScopeProvider> bindIScopeProvider()
    {
        return ContextLinksBslScopeProvider.class;
    }

    public Class<? extends IBslModuleContextDefService> bindIBslModuleContextDefService()
    {
        return ContextLinksModuleContextDefService.class;
    }

    public Class<? extends IContainer.Manager> bindIContainer$Manager()
    {
        return ContextLinksContainerManager.class;
    }
}
