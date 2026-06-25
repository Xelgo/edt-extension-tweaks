package ru.xelgo.edt.contextlinks.core;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;

/**
 * Global feature flags for EDT Extension Tweaks.
 */
public final class ContextLinksPreferences
{
    public static final String KEY_BSL_CONTEXT_LINKS_ENABLED = "bslContextLinks.enabled"; //$NON-NLS-1$
    public static final String KEY_QUERY_WIZARD_ENABLED = "queryWizard.enabled"; //$NON-NLS-1$
    public static final String KEY_QUERY_WIZARD_NESTED_TEMP_TABLES_ENABLED =
        "queryWizard.nestedTempTables.enabled"; //$NON-NLS-1$
    public static final String KEY_INSERT_FORMATTING_ENABLED = "insertFormatting.enabled"; //$NON-NLS-1$
    public static final String KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED = "workbenchViewActivation.enabled"; //$NON-NLS-1$
    public static final String KEY_SERVER_CALL_HIGHLIGHTING_ENABLED = "serverCallHighlighting.enabled"; //$NON-NLS-1$
    public static final String KEY_SERVER_CALL_HIGHLIGHTING_COLOR = "serverCallHighlighting.color"; //$NON-NLS-1$
    public static final String KEY_SERVER_CALL_HIGHLIGHTING_STYLE = "serverCallHighlighting.style"; //$NON-NLS-1$
    public static final String KEY_VARIABLE_SNAPSHOTS_ENABLED = "variableSnapshots.enabled"; //$NON-NLS-1$
    public static final String KEY_VARIABLE_SNAPSHOTS_MAX_DEPTH = "variableSnapshots.maxDepth"; //$NON-NLS-1$
    public static final String KEY_VARIABLE_SNAPSHOTS_COLLECTION_LIMIT =
        "variableSnapshots.collectionLimit"; //$NON-NLS-1$

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_VARIABLE_SNAPSHOTS_MAX_DEPTH = 5;
    private static final int DEFAULT_VARIABLE_SNAPSHOTS_COLLECTION_LIMIT = 300;
    private static final int DEFAULT_SERVER_CALL_HIGHLIGHTING_STYLE = SWT.BOLD;
    private static final RGB DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR = new RGB(176, 89, 0);
    private static final String DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR_VALUE =
        StringConverter.asString(DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
    private static final String DEFAULT_SERVER_CALL_HIGHLIGHTING_STYLE_VALUE =
        Integer.toString(DEFAULT_SERVER_CALL_HIGHLIGHTING_STYLE);

    private ContextLinksPreferences()
    {
        // Utility class.
    }

    public static void initializeDefaultPreferences()
    {
        IEclipsePreferences preferences = DefaultScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID);
        preferences.putBoolean(KEY_BSL_CONTEXT_LINKS_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_QUERY_WIZARD_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_QUERY_WIZARD_NESTED_TEMP_TABLES_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_INSERT_FORMATTING_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_SERVER_CALL_HIGHLIGHTING_ENABLED, DEFAULT_ENABLED);
        preferences.putBoolean(KEY_VARIABLE_SNAPSHOTS_ENABLED, DEFAULT_ENABLED);
        preferences.putInt(KEY_VARIABLE_SNAPSHOTS_MAX_DEPTH, DEFAULT_VARIABLE_SNAPSHOTS_MAX_DEPTH);
        preferences.putInt(KEY_VARIABLE_SNAPSHOTS_COLLECTION_LIMIT, DEFAULT_VARIABLE_SNAPSHOTS_COLLECTION_LIMIT);
        preferences.put(KEY_SERVER_CALL_HIGHLIGHTING_COLOR, DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR_VALUE);
        preferences.put(KEY_SERVER_CALL_HIGHLIGHTING_STYLE, DEFAULT_SERVER_CALL_HIGHLIGHTING_STYLE_VALUE);
    }

    public static boolean isBslContextLinksEnabled()
    {
        return getBoolean(KEY_BSL_CONTEXT_LINKS_ENABLED);
    }

    public static boolean isQueryWizardEnabled()
    {
        return getBoolean(KEY_QUERY_WIZARD_ENABLED);
    }

    public static boolean isQueryWizardNestedTempTablesEnabled()
    {
        return isQueryWizardEnabled() && getBoolean(KEY_QUERY_WIZARD_NESTED_TEMP_TABLES_ENABLED);
    }

    public static boolean isInsertFormattingEnabled()
    {
        return getBoolean(KEY_INSERT_FORMATTING_ENABLED);
    }

    public static boolean isWorkbenchViewActivationEnabled()
    {
        return getBoolean(KEY_WORKBENCH_VIEW_ACTIVATION_ENABLED);
    }

    public static boolean isServerCallHighlightingEnabled()
    {
        return getBoolean(KEY_SERVER_CALL_HIGHLIGHTING_ENABLED);
    }

    public static boolean isVariableSnapshotsEnabled()
    {
        return getBoolean(KEY_VARIABLE_SNAPSHOTS_ENABLED);
    }

    public static int getVariableSnapshotsMaxDepth()
    {
        return getInt(KEY_VARIABLE_SNAPSHOTS_MAX_DEPTH, DEFAULT_VARIABLE_SNAPSHOTS_MAX_DEPTH, 1, 20);
    }

    public static int getVariableSnapshotsCollectionLimit()
    {
        return getInt(KEY_VARIABLE_SNAPSHOTS_COLLECTION_LIMIT, DEFAULT_VARIABLE_SNAPSHOTS_COLLECTION_LIMIT, 1, 5000);
    }

    public static RGB getServerCallHighlightingColor()
    {
        String value = InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID)
            .get(KEY_SERVER_CALL_HIGHLIGHTING_COLOR, DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR_VALUE);
        return StringConverter.asRGB(value, DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
    }

    public static int getServerCallHighlightingStyle()
    {
        String value = InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID)
            .get(KEY_SERVER_CALL_HIGHLIGHTING_STYLE, DEFAULT_SERVER_CALL_HIGHLIGHTING_STYLE_VALUE);
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return DEFAULT_SERVER_CALL_HIGHLIGHTING_STYLE;
        }
    }

    private static boolean getBoolean(String key)
    {
        return InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID).getBoolean(key, DEFAULT_ENABLED);
    }

    private static int getInt(String key, int defaultValue, int minValue, int maxValue)
    {
        int value = InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID).getInt(key, defaultValue);
        return Math.max(minValue, Math.min(maxValue, value));
    }
}
