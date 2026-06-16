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
    public static String ContextLinksPreferencePage_BslContextLinksEnabled;
    public static String ContextLinksPreferencePage_Description;
    public static String ContextLinksPreferencePage_InsertFormattingEnabled;
    public static String ContextLinksPreferencePage_QueryWizardEnabled;
    public static String ChangeAndValidateMergeQuickfix_AncestorLabel;
    public static String ChangeAndValidateMergeQuickfix_Description;
    public static String ChangeAndValidateMergeQuickfix_InvalidMethodRange;
    public static String ChangeAndValidateMergeQuickfix_Label;
    public static String ChangeAndValidateMergeQuickfix_LeftLabel;
    public static String ChangeAndValidateMergeQuickfix_NoExtensionMethod;
    public static String ChangeAndValidateMergeQuickfix_NoMethodNode;
    public static String ChangeAndValidateMergeQuickfix_NoModuleExtensionService;
    public static String ChangeAndValidateMergeQuickfix_NoSourceMethod;
    public static String ChangeAndValidateMergeQuickfix_NoSourceMethodNode;
    public static String ChangeAndValidateMergeQuickfix_NotChangeAndValidateMethod;
    public static String ChangeAndValidateMergeQuickfix_ReadResultFailed;
    public static String ChangeAndValidateMergeQuickfix_ResultLabel;
    public static String ChangeAndValidateMergeQuickfix_RightLabel;
    public static String ChangeAndValidateMergeQuickfix_SaveFailed;
    public static String ChangeAndValidateMergeQuickfix_Title;
    public static String ChangeAndValidateMergeQuickfix_UnknownMethod;

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
