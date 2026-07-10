package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

/**
 * Toggles the Navigator filter that shows only uncommitted Git changes.
 */
public class ContextLinksGitChangedFilterHandler
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
        throws ExecutionException
    {
        ContextLinksGitChangedFilterManager.getInstance().toggle(event);
        return null;
    }
}
