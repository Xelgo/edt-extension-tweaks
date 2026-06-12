package ru.xelgo.edt.contextlinks.core;

import org.eclipse.xtext.service.AbstractGenericModule;

import com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider;

/**
 * Adds project context links to standard BSL scope services.
 */
public class ContextLinksBslRuntimeModule
    extends AbstractGenericModule
{
    public Class<? extends BslCachedScopeProvider> bindBslCachedScopeProvider()
    {
        return ContextLinksCachedScopeProvider.class;
    }

}
