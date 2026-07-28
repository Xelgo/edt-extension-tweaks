package ru.xelgo.edt.contextlinks.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksPreferences;

/**
 * Highlights selected workbench tabs with bold font.
 */
public final class ContextLinksActiveTabBoldFontService
{
    private static final ContextLinksActiveTabBoldFontService INSTANCE = new ContextLinksActiveTabBoldFontService();

    private final Map<CTabFolder, Font> boldFonts = new IdentityHashMap<>();
    private final Set<CTabFolder> installedFolders = new HashSet<>();
    private final Listener selectionListener = this::handleTabSelection;
    private final DisposeListener folderDisposeListener = this::handleFolderDisposed;
    private final IPartListener2 partListener = new WorkbenchPartListener();
    private final IPageListener pageListener = new WorkbenchPageListener();
    private final IWindowListener windowListener = new WorkbenchWindowListener();
    private final IEclipsePreferences.IPreferenceChangeListener preferenceListener = event -> {
        if (ContextLinksPreferences.KEY_ACTIVE_TAB_BOLD_FONT_ENABLED.equals(event.getKey()))
            scheduleRefresh();
    };

    private boolean started;

    private ContextLinksActiveTabBoldFontService()
    {
        // Singleton service.
    }

    public static void start()
    {
        INSTANCE.startInternal();
    }

    private void startInternal()
    {
        if (started || !PlatformUI.isWorkbenchRunning())
            return;

        started = true;
        InstanceScope.INSTANCE.getNode(ContextLinks.PLUGIN_ID).addPreferenceChangeListener(preferenceListener);
        PlatformUI.getWorkbench().addWindowListener(windowListener);
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
        scheduleRefresh();
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;

        window.addPageListener(pageListener);
        for (IWorkbenchPage page : window.getPages())
            page.addPartListener(partListener);
    }

    private void scheduleRefresh()
    {
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (display == null || display.isDisposed())
            return;

        display.asyncExec(() -> {
            if (display.isDisposed())
                return;

            refreshAll();
        });
    }

    private void refreshAll()
    {
        if (!ContextLinksPreferences.isActiveTabBoldFontEnabled())
        {
            clearAll();
            return;
        }

        for (Shell shell : Display.getDefault().getShells())
            installInControlTree(shell);
    }

    private void installInControlTree(Control control)
    {
        if (control == null || control.isDisposed())
            return;

        if (control instanceof CTabFolder)
        {
            install((CTabFolder)control);
            updateFolder((CTabFolder)control);
        }

        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
                installInControlTree(child);
        }
    }

    private void install(CTabFolder folder)
    {
        if (!installedFolders.add(folder))
            return;

        folder.addListener(SWT.Selection, selectionListener);
        folder.addDisposeListener(folderDisposeListener);
    }

    private void updateFolder(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;

        CTabItem selection = folder.getSelection();
        for (CTabItem item : folder.getItems())
        {
            if (item.isDisposed())
                continue;

            item.setFont(item == selection ? getBoldFont(folder) : null);
        }
    }

    private Font getBoldFont(CTabFolder folder)
    {
        Font boldFont = boldFonts.get(folder);
        if (boldFont != null && !boldFont.isDisposed())
            return boldFont;

        Font baseFont = folder.getFont();
        FontData[] fontData = baseFont.getFontData();
        for (FontData data : fontData)
            data.setStyle(data.getStyle() | SWT.BOLD);

        boldFont = new Font(folder.getDisplay(), fontData);
        boldFonts.put(folder, boldFont);
        return boldFont;
    }

    private void clearAll()
    {
        for (CTabFolder folder : new ArrayList<>(installedFolders))
            clearFolder(folder);
    }

    private void clearFolder(CTabFolder folder)
    {
        if (folder != null && !folder.isDisposed())
        {
            for (CTabItem item : folder.getItems())
            {
                if (!item.isDisposed())
                    item.setFont(null);
            }
        }

        disposeBoldFont(folder);
    }

    private void disposeBoldFont(CTabFolder folder)
    {
        Font boldFont = boldFonts.remove(folder);
        if (boldFont != null && !boldFont.isDisposed())
            boldFont.dispose();
    }

    private void handleTabSelection(Event event)
    {
        if (event.widget instanceof CTabFolder && ContextLinksPreferences.isActiveTabBoldFontEnabled())
            updateFolder((CTabFolder)event.widget);
    }

    private void handleFolderDisposed(DisposeEvent event)
    {
        CTabFolder folder = (CTabFolder)event.widget;
        installedFolders.remove(folder);
        disposeBoldFont(folder);
    }

    private final class WorkbenchWindowListener
        implements IWindowListener
    {
        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            hookWindow(window);
            scheduleRefresh();
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
            // Workbench controls dispose their tab folders.
        }

        @Override
        public void windowActivated(IWorkbenchWindow window)
        {
            scheduleRefresh();
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window)
        {
            // No action needed.
        }
    }

    private final class WorkbenchPageListener
        implements IPageListener
    {
        @Override
        public void pageOpened(IWorkbenchPage page)
        {
            page.addPartListener(partListener);
            scheduleRefresh();
        }

        @Override
        public void pageClosed(IWorkbenchPage page)
        {
            page.removePartListener(partListener);
            scheduleRefresh();
        }

        @Override
        public void pageActivated(IWorkbenchPage page)
        {
            scheduleRefresh();
        }
    }

    private final class WorkbenchPartListener
        implements IPartListener2
    {
        @Override
        public void partActivated(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }

        @Override
        public void partClosed(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }

        @Override
        public void partDeactivated(IWorkbenchPartReference partRef)
        {
            // No action needed.
        }

        @Override
        public void partOpened(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }

        @Override
        public void partHidden(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }

        @Override
        public void partVisible(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference partRef)
        {
            scheduleRefresh();
        }
    }
}
