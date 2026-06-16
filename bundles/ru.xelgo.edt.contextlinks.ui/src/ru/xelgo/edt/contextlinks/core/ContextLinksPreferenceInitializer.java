package ru.xelgo.edt.contextlinks.core;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

/**
 * Initializes default EDT Extension Tweaks feature flags.
 */
public class ContextLinksPreferenceInitializer
    extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        ContextLinksPreferences.initializeDefaultPreferences();
    }
}
