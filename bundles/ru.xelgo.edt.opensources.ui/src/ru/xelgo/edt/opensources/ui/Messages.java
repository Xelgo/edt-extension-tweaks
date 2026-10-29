package ru.xelgo.edt.opensources.ui;

import org.eclipse.osgi.util.NLS;

final class Messages
    extends NLS
{
    private static final String BUNDLE_NAME = "ru.xelgo.edt.opensources.ui.messages"; //$NON-NLS-1$

    static String OpenSourcesHandler_NoResource;
    static String OpenSourcesHandler_Title;

    static
    {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages()
    {
    }
}
