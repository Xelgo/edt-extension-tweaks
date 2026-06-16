package ru.xelgo.edt.contextlinks.core;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Global feature flags for EDT Extension Tweaks.
 */
public final class ContextLinksPreferences
{
    public static final String KEY_BSL_CONTEXT_LINKS_ENABLED = "bslContextLinks.enabled"; //$NON-NLS-1$
    public static final String KEY_QUERY_WIZARD_ENABLED = "queryWizard.enabled"; //$NON-NLS-1$
    public static final String KEY_INSERT_FORMATTING_ENABLED = "insertFormatting.enabled"; //$NON-NLS-1$
    public static final String KEY_DIRTY_PREPROCESSOR_ENABLED = "dirtyPreprocessor.experimental.enabled"; //$NON-NLS-1$

    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_DIRTY_PREPROCESSOR_ENABLED = false;
    private static final String DIRTY_PREPROCESSOR_DISABLE_PROPERTY =
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.disable"; //$NON-NLS-1$

    private ContextLinksPreferences()
    {
        // Utility class.
    }

    public static void initializeDefaultPreferences()
    {
        IEclipsePreferences preferences = DefaultScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID);
        preferences.putBoolean(KEY_BSL_CONTEXT_LINKS_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_QUERY_WIZARD_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_INSERT_FORMATTING_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_DIRTY_PREPROCESSOR_ENABLED, DEFAULT_DIRTY_PREPROCESSOR_ENABLED);
    }

    public static boolean isBslContextLinksEnabled()
    {
        return getBoolean(KEY_BSL_CONTEXT_LINKS_ENABLED);
    }

    public static boolean isQueryWizardEnabled()
    {
        return getBoolean(KEY_QUERY_WIZARD_ENABLED);
    }

    public static boolean isInsertFormattingEnabled()
    {
        return getBoolean(KEY_INSERT_FORMATTING_ENABLED);
    }

    public static boolean isDirtyPreprocessorEnabled()
    {
        return !Boolean.getBoolean(DIRTY_PREPROCESSOR_DISABLE_PROPERTY)
            && getBoolean(KEY_DIRTY_PREPROCESSOR_ENABLED, DEFAULT_DIRTY_PREPROCESSOR_ENABLED);
    }

    private static boolean getBoolean(String key)
    {
        return InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID).getBoolean(key, DEFAULT_ENABLED);
    }

    private static boolean getBoolean(String key, boolean defaultValue)
    {
        return InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID).getBoolean(key, defaultValue);
    }
}
