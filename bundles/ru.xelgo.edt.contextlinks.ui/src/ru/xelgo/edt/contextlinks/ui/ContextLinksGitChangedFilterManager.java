package ru.xelgo.edt.contextlinks.ui;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.State;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.RegistryToggleState;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Installs and removes the Git changed filter directly on the EDT Navigator viewer.
 */
final class ContextLinksGitChangedFilterManager
{
    private static final String COMMAND_ID =
        "ru.xelgo.edt.contextlinks.ui.commands.toggleGitChangedNavigatorFilter"; //$NON-NLS-1$
    private static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator"; //$NON-NLS-1$

    private static final ContextLinksGitChangedFilterManager INSTANCE = new ContextLinksGitChangedFilterManager();

    private ContextLinksGitChangedNavigatorFilter filter;
    private boolean active;

    private ContextLinksGitChangedFilterManager()
    {
        // Singleton.
    }

    static ContextLinksGitChangedFilterManager getInstance()
    {
        return INSTANCE;
    }

    void toggle(ExecutionEvent event)
    {
        if (active)
            deactivate(event);
        else
            activate(event);
    }

    private void activate(ExecutionEvent event)
    {
        CommonViewer viewer = getViewer(event);
        if (viewer == null)
        {
            ContextLinks.logWarning("Git changed navigator filter command cannot resolve EDT Navigator viewer"); //$NON-NLS-1$
            updateToggleState(event, false);
            return;
        }

        ContextLinksGitChangedResources.invalidate();
        if (filter == null)
            filter = new ContextLinksGitChangedNavigatorFilter();

        if (!hasFilter(viewer, filter))
            viewer.addFilter(filter);

        active = true;
        updateToggleState(event, true);
        viewer.refresh();
        expandVisibleTree(viewer);
    }

    private void deactivate(ExecutionEvent event)
    {
        CommonViewer viewer = getViewer(event);
        if (viewer != null && filter != null && hasFilter(viewer, filter))
        {
            viewer.removeFilter(filter);
            viewer.refresh();
        }

        active = false;
        updateToggleState(event, false);
    }

    private CommonViewer getViewer(ExecutionEvent event)
    {
        CommonNavigator navigator = resolveNavigator(event);
        if (navigator == null)
            return null;

        CommonViewer viewer = navigator.getCommonViewer();
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return null;

        return viewer;
    }

    private CommonNavigator resolveNavigator(ExecutionEvent event)
    {
        CommonNavigator activeNavigator = adaptNavigator(HandlerUtil.getActivePart(event));
        if (activeNavigator != null)
            return activeNavigator;

        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
            window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        IViewPart navigatorView = page != null ? page.findView(NAVIGATOR_VIEW_ID) : null;
        return adaptNavigator(navigatorView);
    }

    private CommonNavigator adaptNavigator(IWorkbenchPart part)
    {
        if (part instanceof CommonNavigator)
            return (CommonNavigator)part;

        if (part == null)
            return null;

        Object adapted = part.getAdapter(CommonNavigator.class);
        return adapted instanceof CommonNavigator ? (CommonNavigator)adapted : null;
    }

    private boolean hasFilter(CommonViewer viewer, ViewerFilter expectedFilter)
    {
        for (ViewerFilter viewerFilter : viewer.getFilters())
        {
            if (viewerFilter == expectedFilter)
                return true;
        }
        return false;
    }

    private void expandVisibleTree(CommonViewer viewer)
    {
        try
        {
            viewer.expandToLevel(3);
        }
        catch (RuntimeException e)
        {
            // Best effort: expansion must not break the Navigator.
        }
    }

    private void updateToggleState(ExecutionEvent event, boolean value)
    {
        updateEventCommandState(event, value);
        try
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null)
                return;

            Command command = commandService.getCommand(COMMAND_ID);
            State state = command != null ? command.getState(RegistryToggleState.STATE_ID) : null;
            if (state != null)
                state.setValue(Boolean.valueOf(value));

            commandService.refreshElements(COMMAND_ID, null);
        }
        catch (RuntimeException e)
        {
            // Best effort: stale command state is safer than a broken toolbar action.
        }
    }

    private void updateEventCommandState(ExecutionEvent event, boolean value)
    {
        if (event == null || event.getCommand() == null)
            return;

        State state = event.getCommand().getState(RegistryToggleState.STATE_ID);
        if (state != null)
            state.setValue(Boolean.valueOf(value));
    }
}
