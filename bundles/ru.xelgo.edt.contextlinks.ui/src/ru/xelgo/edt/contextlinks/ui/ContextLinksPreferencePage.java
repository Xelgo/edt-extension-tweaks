package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
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
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_INSERT_FORMATTING_ENABLED,
            Messages.ContextLinksPreferencePage_InsertFormattingEnabled, getFieldEditorParent()));
        addField(new BooleanFieldEditor(ContextLinksPreferences.KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED,
            Messages.ContextLinksPreferencePage_WorkbenchViewActivationEnabled, getFieldEditorParent()));
    }
}
