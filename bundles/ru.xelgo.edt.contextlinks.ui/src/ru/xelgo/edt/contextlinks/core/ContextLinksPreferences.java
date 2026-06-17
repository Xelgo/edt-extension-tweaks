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
    public static final String KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED = "workbenchViewActivation.enabled"; //$NON-NLS-1$

    private static final boolean DEFAULT_ENABLED = true;

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
        preferences.putBoolean(KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED, DEFAULT_ENABLED);
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

    public static boolean isWorkbenchViewActivationEnabled()
    {
        return getBoolean(KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED);
    }

    private static boolean getBoolean(String key)
    {
        return InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID).getBoolean(key, DEFAULT_ENABLED);
    }
}
