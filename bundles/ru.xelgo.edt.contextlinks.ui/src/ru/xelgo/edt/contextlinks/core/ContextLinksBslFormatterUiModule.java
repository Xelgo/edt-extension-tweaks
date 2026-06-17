package ru.xelgo.edt.contextlinks.core;

import org.eclipse.xtext.formatting2.IFormatter2;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.service.AbstractGenericModule;
import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfiguration;

/**
 * Extends BSL editor UI services.
 */
public class ContextLinksBslFormatterUiModule
    extends AbstractGenericModule
{
    public Class<? extends IFormatter2> bindIFormatter2()
    {
        return ContextLinksBslFormatter.class;
    }

    public Class<? extends ISemanticHighlightingCalculator> bindISemanticHighlightingCalculator()
    {
        return ContextLinksBslServerCallHighlightingCalculator.class;
    }

    public Class<? extends IHighlightingConfiguration> bindIHighlightingConfiguration()
    {
        return ContextLinksBslServerCallHighlightingConfiguration.class;
    }
}
