package ru.xelgo.edt.contextlinks.ui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.State;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.project.GitProjectData;
import org.eclipse.egit.core.project.RepositoryMappingChangeListener;
import org.eclipse.jgit.events.ListenerHandle;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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
public final class ContextLinksGitChangedFilterManager
{
    private static final String COMMAND_ID =
        "ru.xelgo.edt.contextlinks.ui.commands.toggleGitChangedNavigatorFilter"; //$NON-NLS-1$
    private static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator"; //$NON-NLS-1$

    private static final ContextLinksGitChangedFilterManager INSTANCE = new ContextLinksGitChangedFilterManager();

    private final Map<CommonViewer, Session> sessions = new IdentityHashMap<>();

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
        CommonViewer viewer = getViewer(event);
        if (viewer == null)
        {
            ContextLinks.logWarning("Git changed navigator filter command cannot resolve EDT Navigator viewer"); //$NON-NLS-1$
            updateToggleState(event, false);
            return;
        }

        Session current;
        synchronized (sessions)
        {
            current = sessions.get(viewer);
        }
        if (current != null)
        {
            deactivate(current);
            return;
        }

        Session session = new Session(viewer, event);
        synchronized (sessions)
        {
            sessions.put(viewer, session);
        }
        updateToggleState(event, true);
        session.start();
    }

    private void deactivate(Session session)
    {
        synchronized (sessions)
        {
            sessions.remove(session.viewer);
        }
        session.close();
        if (!session.control.isDisposed())
        {
            session.control.removeDisposeListener(session.disposeListener);
            if (hasFilter(session.viewer, session.filter))
                session.viewer.removeFilter(session.filter);
        }
        updateToggleState(session.event, false);
    }

    public static void shutdown()
    {
        List<Session> current;
        synchronized (INSTANCE.sessions)
        {
            current = new ArrayList<>(INSTANCE.sessions.values());
            INSTANCE.sessions.clear();
        }
        for (Session session : current)
            session.close();
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

    static boolean hasRelevantChanges(IResourceDelta delta)
    {
        if (delta == null)
            return false;
        if (delta.getKind() == IResourceDelta.ADDED || delta.getKind() == IResourceDelta.REMOVED
            || (delta.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED | IResourceDelta.OPEN
                | IResourceDelta.DESCRIPTION | IResourceDelta.TYPE | IResourceDelta.ENCODING
                | IResourceDelta.LOCAL_CHANGED)) != 0)
            return true;
        for (IResourceDelta child : delta.getAffectedChildren())
        {
            if (hasRelevantChanges(child))
                return true;
        }
        return false;
    }

    private final class Session
    {
        private final CommonViewer viewer;
        private final ExecutionEvent event;
        private final Control control;
        private final Display display;
        private final ContextLinksGitChangedNavigatorFilter filter = new ContextLinksGitChangedNavigatorFilter();
        private final ContextLinksGitChangedRefreshJob job;
        private final List<ListenerHandle> gitListeners = new ArrayList<>();
        private final IResourceChangeListener resourceListener;
        private final RepositoryMappingChangeListener mappingListener;
        private final DisposeListener disposeListener;
        private volatile ContextLinksGitChangedResources.Snapshot snapshot;
        private volatile boolean closed;

        Session(CommonViewer viewer, ExecutionEvent event)
        {
            this.viewer = viewer;
            this.event = event;
            control = viewer.getControl();
            display = control.getDisplay();
            job = new ContextLinksGitChangedRefreshJob(ContextLinksGitChangedResources::readWorkspaceSnapshot,
                this::asyncExec, this::publish, this::scanCanceled);
            resourceListener = change -> {
                if (hasRelevantChanges(change.getDelta()))
                    requestRefresh();
            };
            mappingListener = mapping -> requestRefresh();
            disposeListener = disposed -> {
                synchronized (sessions)
                {
                    sessions.remove(viewer);
                }
                close();
            };
        }

        synchronized void start()
        {
            if (closed)
                return;
            control.addDisposeListener(disposeListener);
            ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
            GitProjectData.addRepositoryChangeListener(mappingListener);
            gitListeners.add(Repository.getGlobalListenerList().addIndexChangedListener(
                event -> repositoryChanged(event.getRepository())));
            gitListeners.add(Repository.getGlobalListenerList().addRefsChangedListener(
                event -> repositoryChanged(event.getRepository())));
            gitListeners.add(Repository.getGlobalListenerList().addWorkingTreeModifiedListener(
                event -> repositoryChanged(event.getRepository())));
            job.request(0L);
        }

        void repositoryChanged(Repository repository)
        {
            ContextLinksGitChangedResources.Snapshot current = snapshot;
            if (current == null || current.containsRepository(repository))
                requestRefresh();
        }

        void requestRefresh()
        {
            if (!closed)
                job.request(500L);
        }

        void publish(ContextLinksGitChangedResources.Snapshot ready)
        {
            if (closed || control.isDisposed())
                return;
            snapshot = ready;
            filter.setSnapshot(ready);
            if (!hasFilter(viewer, filter))
                viewer.addFilter(filter);
            else
                viewer.refresh();
        }

        void scanCanceled()
        {
            if (!closed && snapshot == null && !control.isDisposed())
                deactivate(this);
        }

        void asyncExec(Runnable runnable)
        {
            if (!closed && !display.isDisposed())
            {
                try
                {
                    display.asyncExec(runnable);
                }
                catch (org.eclipse.swt.SWTException e)
                {
                    if (!display.isDisposed())
                        throw e;
                }
            }
        }

        synchronized void close()
        {
            if (closed)
                return;
            closed = true;
            job.close();
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
            GitProjectData.removeRepositoryChangeListener(mappingListener);
            for (ListenerHandle listener : gitListeners)
                listener.remove();
            gitListeners.clear();
        }
    }
}
