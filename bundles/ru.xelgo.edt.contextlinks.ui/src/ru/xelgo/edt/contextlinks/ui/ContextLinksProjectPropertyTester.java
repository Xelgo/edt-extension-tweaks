package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.core.expressions.PropertyTester;

/**
 * Controls navigator menu visibility for EDT project selections.
 */
public class ContextLinksProjectPropertyTester
    extends PropertyTester
{
    private static final String IS_EXTENSION_PROJECT = "isExtensionProject"; //$NON-NLS-1$

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue)
    {
        if (!IS_EXTENSION_PROJECT.equals(property))
            return false;

        return ProjectSelection.isExtensionProject(receiver);
    }
}
