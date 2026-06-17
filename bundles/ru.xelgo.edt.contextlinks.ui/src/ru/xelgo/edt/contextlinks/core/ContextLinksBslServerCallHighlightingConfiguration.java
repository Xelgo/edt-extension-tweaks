package ru.xelgo.edt.contextlinks.core;

import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfiguration;
import org.eclipse.xtext.ui.editor.syntaxcoloring.IHighlightingConfigurationAcceptor;
import org.eclipse.xtext.ui.editor.utils.TextStyle;
import org.osgi.framework.Bundle;

import org.eclipse.core.runtime.Platform;

/**
 * Adds a separate syntax coloring preference for server calls from client code.
 */
public final class ContextLinksBslServerCallHighlightingConfiguration
    implements IHighlightingConfiguration
{
    public static final String SERVER_CALL_ID = ContextLinks.PLUGIN_ID + ".serverCall"; //$NON-NLS-1$

    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String BSL_HIGHLIGHTING_CONFIGURATION =
        "com._1c.g5.v8.dt.bsl.ui.syntaxcoloring.BslHighlightingConfiguration"; //$NON-NLS-1$
    private static final String SERVER_CALL_LABEL =
        "\u0421\u0435\u0440\u0432\u0435\u0440\u043d\u044b\u0435 \u0432\u044b\u0437\u043e\u0432\u044b \u0438\u0437 \u043a\u043b\u0438\u0435\u043d\u0442\u0441\u043a\u043e\u0433\u043e \u043a\u043e\u0434\u0430"; //$NON-NLS-1$

    private final IHighlightingConfiguration delegate = createNativeDelegate();

    @Override
    public void configure(IHighlightingConfigurationAcceptor acceptor)
    {
        if (delegate != null)
            delegate.configure(acceptor);

        acceptor.acceptDefaultHighlighting(SERVER_CALL_ID, SERVER_CALL_LABEL, serverCallTextStyle());
    }

    public TextStyle serverCallTextStyle()
    {
        TextStyle textStyle = new TextStyle();
        textStyle.setColor(ContextLinksPreferences.getServerCallHighlightingColor());
        textStyle.setStyle(ContextLinksPreferences.getServerCallHighlightingStyle());
        return textStyle;
    }

    private static IHighlightingConfiguration createNativeDelegate()
    {
        Bundle bundle = Platform.getBundle(BSL_UI_BUNDLE);
        if (bundle == null)
            return null;

        try
        {
            Object instance = bundle.loadClass(BSL_HIGHLIGHTING_CONFIGURATION).getDeclaredConstructor().newInstance();
            if (instance instanceof IHighlightingConfiguration)
                return (IHighlightingConfiguration)instance;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            ContextLinks.logError("Failed to create native BSL highlighting configuration.", e); //$NON-NLS-1$
        }
        return null;
    }
}
