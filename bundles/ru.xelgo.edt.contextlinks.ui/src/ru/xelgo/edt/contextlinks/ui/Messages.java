package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;

/**
 * Localized UI strings.
 */
public final class Messages
    extends NLS
{
    private static final String BUNDLE_NAME = "ru.xelgo.edt.contextlinks.ui.messages"; //$NON-NLS-1$

    public static String ConfigureContextLinksHandler_Message;
    public static String ConfigureContextLinksHandler_NoProject;
    public static String ConfigureContextLinksHandler_NotExtensionProject;
    public static String ConfigureContextLinksHandler_Title;
    public static String ConfigureApplicationUpdateProjectsHandler_Message;
    public static String ConfigureApplicationUpdateProjectsHandler_NoApplication;
    public static String ConfigureApplicationUpdateProjectsHandler_NoCandidates;
    public static String ConfigureApplicationUpdateProjectsHandler_Title;

    static
    {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages()
    {
        // Utility class.
    }

    static void showInfo(Shell shell, String title, String message)
    {
        MessageDialog.openInformation(shell, title, message);
    }
}
