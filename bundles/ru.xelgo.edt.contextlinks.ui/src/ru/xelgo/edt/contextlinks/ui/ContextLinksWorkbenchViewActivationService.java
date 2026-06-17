package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksPreferences;

/**
 * Keeps the Outline and Properties views aligned with the active EDT editor.
 */
public final class ContextLinksWorkbenchViewActivationService
{
    private static final String BSL_EDITOR_ID = "com._1c.g5.v8.dt.bsl.Bsl"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$
    private static final String ORDINARY_FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.ordinaryFormEditor"; //$NON-NLS-1$
    private static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator"; //$NON-NLS-1$
    private static final String OUTLINE_VIEW_ID = "org.eclipse.ui.views.ContentOutline"; //$NON-NLS-1$
    private static final String PROPERTY_SHEET_VIEW_ID = "org.eclipse.ui.views.PropertySheet"; //$NON-NLS-1$
    private static final String FORM_EDITOR_PAGE_CLASS = "com._1c.g5.v8.dt.form.ui.editor.FormEditorPage"; //$NON-NLS-1$
    private static final String FORM_EDITOR_MODULE_PAGE_CLASS =
        "com._1c.g5.v8.dt.form.ui.editor.FormEditorModulePage"; //$NON-NLS-1$

    private static final ContextLinksWorkbenchViewActivationService INSTANCE =
        new ContextLinksWorkbenchViewActivationService();

    private final Set<IWorkbenchWindow> registeredWindows =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<IWorkbenchPage> registeredPages =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<IPageChangeProvider, IPageChangedListener> pageChangedListeners =
        new IdentityHashMap<>();
    private final IWindowListener windowListener = new WorkbenchWindowListener();
    private final IPageListener pageListener = new WorkbenchPageListener();
    private final IPartListener2 partListener = new WorkbenchPartListener();

    private boolean installed;

    private ContextLinksWorkbenchViewActivationService()
    {
        // Singleton.
    }

    public static void start()
    {
        INSTANCE.scheduleInstall();
    }

    private void scheduleInstall()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;

        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed())
            return;

        display.asyncExec(this::install);
    }

    private void install()
    {
        if (installed || !PlatformUI.isWorkbenchRunning() || !ContextLinksPreferences.isWorkbenchViewActivationEnabled())
            return;

        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.addWindowListener(windowListener);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            registerWindow(window);

        installed = true;
        ContextLinks.logDebug("EDT Extension Tweaks workbench view activation listener registered"); //$NON-NLS-1$
    }

    private void registerWindow(IWorkbenchWindow window)
    {
        if (window == null || !registeredWindows.add(window))
            return;

        window.addPageListener(pageListener);
        for (IWorkbenchPage page : window.getPages())
            registerPage(page);
    }

    private void unregisterWindow(IWorkbenchWindow window)
    {
        if (window == null || !registeredWindows.remove(window))
            return;

        window.removePageListener(pageListener);
        for (IWorkbenchPage page : window.getPages())
            unregisterPage(page);
    }

    private void registerPage(IWorkbenchPage page)
    {
        if (page == null || !registeredPages.add(page))
            return;

        page.addPartListener(partListener);
        IWorkbenchPartReference activePartReference = page.getActivePartReference();
        if (activePartReference != null)
            handlePartActivated(activePartReference);
    }

    private void unregisterPage(IWorkbenchPage page)
    {
        if (page == null || !registeredPages.remove(page))
            return;

        page.removePartListener(partListener);
    }

    private void handlePartActivated(IWorkbenchPartReference partReference)
    {
        if (!ContextLinksPreferences.isWorkbenchViewActivationEnabled())
            return;

        IWorkbenchPart part = partReference.getPart(false);
        registerPageChangeProvider(partReference, part);

        String targetViewId = getTargetViewId(partReference, part);
        if (targetViewId == null)
            return;

        IWorkbenchPage page = partReference.getPage();
        if (page == null)
            return;

        showRelatedView(page, targetViewId, "source=" + partReference.getId()); //$NON-NLS-1$
    }

    private void handleFormEditorPageChanged(IWorkbenchPage page, Object selectedPage)
    {
        if (!ContextLinksPreferences.isWorkbenchViewActivationEnabled())
            return;

        String targetViewId = getTargetViewIdForFormEditorPage(selectedPage);
        if (targetViewId == null || page == null)
            return;

        showRelatedView(page, targetViewId, "formPage=" + selectedPage.getClass().getName()); //$NON-NLS-1$
    }

    private void showRelatedView(IWorkbenchPage page, String targetViewId, String sourceDescription)
    {
        if (!isRelatedViewSwitchAllowed(page, targetViewId))
            return;

        try
        {
            page.showView(targetViewId, null, IWorkbenchPage.VIEW_VISIBLE);
            ContextLinks.logDebug("EDT Extension Tweaks switched related view: " + sourceDescription //$NON-NLS-1$
                + " target=" + targetViewId); //$NON-NLS-1$
        }
        catch (PartInitException e)
        {
            ContextLinks.logError("Failed to switch related EDT view " + targetViewId //$NON-NLS-1$
                + " for " + sourceDescription, e); //$NON-NLS-1$
        }
    }

    private boolean isRelatedViewSwitchAllowed(IWorkbenchPage page, String targetViewId)
    {
        String expectedVisibleViewId = getExpectedVisibleViewId(targetViewId);
        return expectedVisibleViewId != null && isViewVisible(page, expectedVisibleViewId);
    }

    private String getExpectedVisibleViewId(String targetViewId)
    {
        if (OUTLINE_VIEW_ID.equals(targetViewId))
            return PROPERTY_SHEET_VIEW_ID;

        if (PROPERTY_SHEET_VIEW_ID.equals(targetViewId))
            return OUTLINE_VIEW_ID;

        return null;
    }

    private boolean isViewVisible(IWorkbenchPage page, String viewId)
    {
        IWorkbenchPartReference viewReference = page.findViewReference(viewId);
        if (viewReference == null)
            return false;

        IWorkbenchPart view = viewReference.getPart(false);
        return view != null && page.isPartVisible(view);
    }

    private String getTargetViewId(IWorkbenchPartReference partReference, IWorkbenchPart part)
    {
        if (partReference == null)
            return null;

        String partId = partReference.getId();
        if (BSL_EDITOR_ID.equals(partId))
            return OUTLINE_VIEW_ID;

        if (NAVIGATOR_VIEW_ID.equals(partId))
            return PROPERTY_SHEET_VIEW_ID;

        if (isFormEditor(partId))
        {
            String formEditorPageTargetViewId = getTargetViewIdForFormEditorPage(getActiveFormEditorPage(part));
            return formEditorPageTargetViewId == null ? PROPERTY_SHEET_VIEW_ID : formEditorPageTargetViewId;
        }

        return null;
    }

    private void registerPageChangeProvider(IWorkbenchPartReference partReference, IWorkbenchPart part)
    {
        if (partReference == null || !isFormEditor(partReference.getId()) || !(part instanceof IPageChangeProvider))
            return;

        IPageChangeProvider provider = (IPageChangeProvider)part;
        if (pageChangedListeners.containsKey(provider))
            return;

        IWorkbenchPage page = partReference.getPage();
        IPageChangedListener listener = event -> handleFormEditorPageChanged(page, event.getSelectedPage());
        provider.addPageChangedListener(listener);
        pageChangedListeners.put(provider, listener);
    }

    private void unregisterPageChangeProvider(IWorkbenchPartReference partReference)
    {
        if (partReference == null)
            return;

        IWorkbenchPart part = partReference.getPart(false);
        if (!(part instanceof IPageChangeProvider))
            return;

        IPageChangeProvider provider = (IPageChangeProvider)part;
        IPageChangedListener listener = pageChangedListeners.remove(provider);
        if (listener != null)
            provider.removePageChangedListener(listener);
    }

    private String getTargetViewIdForFormEditorPage(Object selectedPage)
    {
        if (selectedPage == null)
            return null;

        String pageClassName = selectedPage.getClass().getName();
        if (FORM_EDITOR_MODULE_PAGE_CLASS.equals(pageClassName))
            return OUTLINE_VIEW_ID;

        if (FORM_EDITOR_PAGE_CLASS.equals(pageClassName))
            return PROPERTY_SHEET_VIEW_ID;

        return null;
    }

    private Object getActiveFormEditorPage(IWorkbenchPart part)
    {
        if (part == null)
            return null;

        Class<?> type = part.getClass();
        while (type != null)
        {
            try
            {
                Method method = type.getDeclaredMethod("getActivePageInstance"); //$NON-NLS-1$
                method.setAccessible(true);
                return method.invoke(part);
            }
            catch (NoSuchMethodException e)
            {
                type = type.getSuperclass();
            }
            catch (IllegalAccessException | InvocationTargetException | RuntimeException e)
            {
                ContextLinks.logDebug("Failed to read active EDT form editor page: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }
        }

        return null;
    }

    private boolean isFormEditor(String partId)
    {
        return FORM_EDITOR_ID.equals(partId) || ORDINARY_FORM_EDITOR_ID.equals(partId);
    }

    private final class WorkbenchWindowListener
        implements IWindowListener
    {
        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerWindow(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
            unregisterWindow(window);
        }

        @Override
        public void windowActivated(IWorkbenchWindow window)
        {
            // No action needed.
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
            registerPage(page);
        }

        @Override
        public void pageClosed(IWorkbenchPage page)
        {
            unregisterPage(page);
        }

        @Override
        public void pageActivated(IWorkbenchPage page)
        {
            // No action needed.
        }
    }

    private final class WorkbenchPartListener
        implements IPartListener2
    {
        @Override
        public void partActivated(IWorkbenchPartReference partReference)
        {
            handlePartActivated(partReference);
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference partReference)
        {
            // No action needed.
        }

        @Override
        public void partClosed(IWorkbenchPartReference partReference)
        {
            unregisterPageChangeProvider(partReference);
        }

        @Override
        public void partDeactivated(IWorkbenchPartReference partReference)
        {
            // No action needed.
        }

        @Override
        public void partOpened(IWorkbenchPartReference partReference)
        {
            // No action needed.
        }

        @Override
        public void partHidden(IWorkbenchPartReference partReference)
        {
            // No action needed.
        }

        @Override
        public void partVisible(IWorkbenchPartReference partReference)
        {
            // No action needed.
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference partReference)
        {
            // No action needed.
        }
    }
}
