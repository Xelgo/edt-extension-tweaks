package ru.xelgo.edt.contextlinks.core;

/**
 * Registers OSGi service wrappers used outside the BSL Guice module.
 */
public final class ContextLinksServiceRegistrars
{
    private ContextLinksServiceRegistrars()
    {
        // Utility class.
    }

    public static void ensureRegistered()
    {
        ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered();
        ContextLinksModelObjectAdopterRegistrar.ensureRegistered();
    }
}
