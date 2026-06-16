package ru.xelgo.edt.contextlinks.core;

import org.eclipse.xtext.formatting2.IFormatter2;
import org.eclipse.xtext.service.AbstractGenericModule;

/**
 * Overrides the BSL editor formatter binding in the UI injector.
 */
public class ContextLinksBslFormatterUiModule
    extends AbstractGenericModule
{
    public Class<? extends IFormatter2> bindIFormatter2()
    {
        return ContextLinksBslFormatter.class;
    }
}
