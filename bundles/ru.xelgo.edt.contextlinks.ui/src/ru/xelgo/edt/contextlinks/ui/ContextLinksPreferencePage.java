package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksPreferences;

/**
 * Preferences page for enabling individual EDT Extension Tweaks mechanisms.
 */
public class ContextLinksPreferencePage
    extends FieldEditorPreferencePage
    implements IWorkbenchPreferencePage
{
    public ContextLinksPreferencePage()
    {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, ContextLinks.PLUGIN_ID));
        setDescription(Messages.ContextLinksPreferencePage_Description);
    }

    @Override
    public void init(IWorkbench workbench)
    {
        // No workbench-specific initialization.
    }

    @Override
    protected void createFieldEditors()
    {
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_BSL_CONTEXT_LINKS_ENABLED,
            Messages.ContextLinksPreferencePage_BslContextLinksEnabled, getFieldEditorParent()));
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_QUERY_WIZARD_ENABLED,
            Messages.ContextLinksPreferencePage_QueryWizardEnabled, getFieldEditorParent()));
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_QUERY_WIZARD_NESTED_TEMP_TABLES_ENABLED,
            Messages.ContextLinksPreferencePage_QueryWizardNestedTempTablesEnabled, getFieldEditorParent()));
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_INSERT_FORMATTING_ENABLED,
            Messages.ContextLinksPreferencePage_InsertFormattingEnabled, getFieldEditorParent()));
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED,
            Messages.ContextLinksPreferencePage_WorkbenchViewActivationEnabled, getFieldEditorParent()));
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_SERVER_CALL_HIGHLIGHTING_ENABLED,
            Messages.ContextLinksPreferencePage_ServerCallHighlightingEnabled, getFieldEditorParent()));
        addField(new ColorFieldEditor(ContextLinksPreferences.KEY_SERVER_CALL_HIGHLIGHTING_COLOR,
            Messages.ContextLinksPreferencePage_ServerCallHighlightingColor, getFieldEditorParent()));
        addField(new ComboFieldEditor(ContextLinksPreferences.KEY_SERVER_CALL_HIGHLIGHTING_STYLE,
            Messages.ContextLinksPreferencePage_ServerCallHighlightingStyle, serverCallHighlightingStyleValues(),
            getFieldEditorParent()));
    }

    private String[][] serverCallHighlightingStyleValues()
    {
        return new String[][] {
            { Messages.ContextLinksPreferencePage_ServerCallHighlightingStyleNormal, Integer.toString(SWT.NORMAL) },
            { Messages.ContextLinksPreferencePage_ServerCallHighlightingStyleBold, Integer.toString(SWT.BOLD) },
            { Messages.ContextLinksPreferencePage_ServerCallHighlightingStyleItalic, Integer.toString(SWT.ITALIC) },
            { Messages.ContextLinksPreferencePage_ServerCallHighlightingStyleBoldItalic,
                Integer.toString(SWT.BOLD | SWT.ITALIC) } };
    }
}
