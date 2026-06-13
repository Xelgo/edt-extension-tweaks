package ru.xelgo.edt.contextlinks.core;

import org.eclipse.xtext.service.AbstractGenericModule;

import com._1c.g5.v8.dt.ql.scoping.IQlCachedScopeProvider;

/**
 * Adds project context links to standard QL scope services.
 */
public class ContextLinksQlRuntimeModule
    extends AbstractGenericModule
{
    public ContextLinksQlRuntimeModule()
    {
        ContextLinks.logDebug("EDT Context Links QL runtime module constructed"); //$NON-NLS-1$
    }

    public Class<? extends IQlCachedScopeProvider> bindIQlCachedScopeProvider()
    {
        ContextLinks.logDebug("EDT Context Links QL runtime module binding IQlCachedScopeProvider -> " //$NON-NLS-1$
            + ContextLinksQlCachedScopeProvider.class.getName());
        return ContextLinksQlCachedScopeProvider.class;
    }
}
