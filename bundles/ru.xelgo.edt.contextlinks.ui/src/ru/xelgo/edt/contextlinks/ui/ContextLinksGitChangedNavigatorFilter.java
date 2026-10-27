package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Navigator filter that leaves only elements related to uncommitted Git changes.
 */
public class ContextLinksGitChangedNavigatorFilter
    extends ViewerFilter
{
    private static volatile boolean loggedFailure;
    private ContextLinksGitChangedResources resources;

    void setSnapshot(ContextLinksGitChangedResources.Snapshot snapshot)
    {
        resources = new ContextLinksGitChangedResources(snapshot);
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        try
        {
            return resources == null || resources.isChangedElement(parentElement, element);
        }
        catch (RuntimeException e)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                ContextLinks.logWarning("Failed to apply Git changed navigator filter: " + e.getMessage()); //$NON-NLS-1$
            }
            return true;
        }
    }
}
