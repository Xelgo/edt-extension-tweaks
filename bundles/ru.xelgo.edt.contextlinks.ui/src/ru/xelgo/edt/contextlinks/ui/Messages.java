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
    public static String GenerateQueryResultCommentHandler_Copied;
    public static String GenerateQueryResultCommentHandler_Failed;
    public static String GenerateQueryResultCommentHandler_NoQueryEditor;
    public static String GenerateQueryResultCommentHandler_Title;
    public static String ConfigureApplicationUpdateProjectsHandler_Message;
    public static String ConfigureApplicationUpdateProjectsHandler_NoApplication;
    public static String ConfigureApplicationUpdateProjectsHandler_NoCandidates;
    public static String ConfigureApplicationUpdateProjectsHandler_Title;
    public static String ForceUpdateApplicationExtensionHandler_JobName;
    public static String ForceUpdateApplicationExtensionHandler_Message;
    public static String ForceUpdateApplicationExtensionHandler_NoApplication;
    public static String ForceUpdateApplicationExtensionHandler_NoCallback;
    public static String ForceUpdateApplicationExtensionHandler_NoExtensions;
    public static String ForceUpdateApplicationExtensionHandler_NoSynchronizationManager;
    public static String ForceUpdateApplicationExtensionHandler_Title;
    public static String ForceUpdateApplicationExtensionHandler_UpdateFailed;
    public static String ContextLinksPreferencePage_BslContextLinksEnabled;
    public static String ContextLinksPreferencePage_Description;
    public static String ContextLinksPreferencePage_InsertFormattingEnabled;
    public static String ContextLinksPreferencePage_MethodParameterInfoAutoShow;
    public static String ContextLinksPreferencePage_MethodParameterInfoShowDescription;
    public static String ContextLinksPreferencePage_MethodCompletionCaretToFirstParameter;
    public static String ContextLinksPreferencePage_QueryWizardEnabled;
    public static String ContextLinksPreferencePage_QueryWizardNestedTempTablesEnabled;
    public static String ContextLinksPreferencePage_QueryResultContextModel;
    public static String ContextLinksPreferencePage_QueryResultContextModelSelection;
    public static String ContextLinksPreferencePage_QueryResultContextModelValueTable;
    public static String ContextLinksPreferencePage_ServerCallHighlightingColor;
    public static String ContextLinksPreferencePage_ServerCallHighlightingEnabled;
    public static String ContextLinksPreferencePage_ServerCallHighlightingStyle;
    public static String ContextLinksPreferencePage_ServerCallHighlightingStyleBold;
    public static String ContextLinksPreferencePage_ServerCallHighlightingStyleBoldItalic;
    public static String ContextLinksPreferencePage_ServerCallHighlightingStyleItalic;
    public static String ContextLinksPreferencePage_ServerCallHighlightingStyleNormal;
    public static String ContextLinksPreferencePage_VariableSnapshotsCollectionLimit;
    public static String ContextLinksPreferencePage_VariableSnapshotsEnabled;
    public static String ContextLinksPreferencePage_VariableSnapshotsMaxDepth;
    public static String ContextLinksPreferencePage_WorkbenchViewActivationEnabled;
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

    public static String ChangeAndValidateBatch_DialogTitle;
    public static String ChangeAndValidateBatch_Counter;
    public static String ChangeAndValidateBatch_CounterWarnings;
    public static String ChangeAndValidateBatch_ApplyButton;
    public static String ChangeAndValidateBatch_ConfirmDiscard;
    public static String ChangeAndValidateBatch_NothingFound;
    public static String ChangeAndValidateBatch_NoSuitableMarkers;
    public static String ChangeAndValidateBatch_CollectTask;
    public static String ChangeAndValidateBatch_CollectFailed;
    public static String ChangeAndValidateBatch_ApplyTask;
    public static String ChangeAndValidateBatch_ReportApplied;
    public static String ChangeAndValidateBatch_ReportSkipped;
    public static String ChangeAndValidateBatch_WarnUnbalancedDirectives;
    public static String ChangeAndValidateBatch_WarnEmptyResult;
    public static String ChangeAndValidateBatch_WarnNoBaseProject;
    public static String ChangeAndValidateBatch_WarnNoBaseModule;
    public static String ChangeAndValidateBatch_WarnNoBaseMethod;
    public static String ChangeAndValidateBatch_SkipFileMissing;
    public static String ChangeAndValidateBatch_SkipReadOnly;
    public static String ChangeAndValidateBatch_SkipNoBuffer;
    public static String ChangeAndValidateBatch_SkipModuleChanged;

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

    static void showError(Shell shell, String title, String message)
    {
        MessageDialog.openError(shell, title, message);
    }
}
