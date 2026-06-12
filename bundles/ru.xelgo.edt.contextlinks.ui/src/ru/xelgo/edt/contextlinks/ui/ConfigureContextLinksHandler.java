package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.wiring.ServiceAccess;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Configures additional projects whose BSL context should be visible from the selected EDT project.
 */
public class ConfigureContextLinksHandler
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IProject project = getSelectedProject(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null)
        {
            logWarning("EDT Context Links configure command cannot resolve selected project"); //$NON-NLS-1$
            Messages.showInfo(shell, Messages.ConfigureContextLinksHandler_Title,
                Messages.ConfigureContextLinksHandler_NoProject);
            return null;
        }
        logWarning("EDT Context Links configure command selected project " + project.getName()); //$NON-NLS-1$

        IProject[] candidates = Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(IProject::isAccessible)
            .filter(candidate -> !candidate.equals(project))
            .toArray(IProject[]::new);

        ListSelectionDialog dialog = ListSelectionDialog.of(candidates)
            .contentProvider(ArrayContentProvider.getInstance())
            .labelProvider(new LabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return element instanceof IProject ? ((IProject)element).getName() : super.getText(element);
                }
            })
            .message(Messages.ConfigureContextLinksHandler_Message)
            .preselect(getInitialSelection(project, candidates))
            .title(Messages.ConfigureContextLinksHandler_Title)
            .create(shell);

        if (dialog.open() != Window.OK)
            return null;

        Set<String> names = new LinkedHashSet<>();
        for (Object result : dialog.getResult())
        {
            if (result instanceof IProject)
                names.add(((IProject)result).getName());
        }

        try
        {
            ContextLinks.setContextProjectNames(project, names);
        }
        catch (CoreException e)
        {
            throw new ExecutionException("Failed to save EDT context links", e); //$NON-NLS-1$
        }

        return null;
    }

    private Object[] getInitialSelection(IProject project, IProject[] candidates)
    {
        Set<String> selectedNames = ContextLinks.getContextProjectNames(project);
        return Arrays.stream(candidates)
            .filter(candidate -> selectedNames.contains(candidate.getName()))
            .toArray();
    }

    private IProject getSelectedProject(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        Object selected = selection instanceof IStructuredSelection
            ? ((IStructuredSelection)selection).getFirstElement()
            : null;
        IProject project = getProject(selected);
        if (project != null)
            return project;

        return getActiveEditorProject(event);
    }

    private IProject getProject(Object selected)
    {
        IProject project = adapt(selected, IProject.class);
        if (project != null)
            return project;

        IResource resource = adapt(selected, IResource.class);
        if (resource != null)
            return resource.getProject();

        EObject modelObject = getModelObject(selected);
        if (modelObject == null)
            return null;

        IResourceLookup resourceLookup = ServiceAccess.get(IResourceLookup.class);
        if (resourceLookup == null)
            return null;

        IProject modelProject = resourceLookup.getProject(modelObject);
        if (modelProject != null)
            return modelProject;

        IResource modelResource = resourceLookup.getPlatformResource(modelObject);
        return modelResource != null ? modelResource.getProject() : null;
    }

    private IProject getActiveEditorProject(ExecutionEvent event)
    {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor != null)
        {
            IProject project = getProject(editor.getEditorInput());
            if (project != null)
                return project;
        }

        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        IEditorPart activeEditor = page != null ? page.getActiveEditor() : null;
        if (activeEditor == null)
            return null;

        IEditorInput input = activeEditor.getEditorInput();
        return getProject(input);
    }

    private EObject getModelObject(Object selected)
    {
        EObject modelObject = adapt(selected, EObject.class);
        if (modelObject != null)
            return modelObject;

        String[] methodNames = { "getModelObject", "getModel", "getEObject", "getObject", "getTarget" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String methodName : methodNames)
        {
            Object value = invokeNoArg(selected, methodName);
            if (value instanceof EObject)
                return (EObject)value;
        }
        return null;
    }

    private Object invokeNoArg(Object target, String methodName)
    {
        if (target == null)
            return null;

        try
        {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() == 0)
                return method.invoke(target);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException e)
        {
            return null;
        }
        return null;
    }

    private <T> T adapt(Object object, Class<T> type)
    {
        if (type.isInstance(object))
            return type.cast(object);
        if (object instanceof IAdaptable)
        {
            Object adapted = ((IAdaptable)object).getAdapter(type);
            if (type.isInstance(adapted))
                return type.cast(adapted);
        }
        return null;
    }

    private static void logWarning(String message)
    {
        Platform.getLog(ConfigureContextLinksHandler.class)
            .log(new Status(IStatus.WARNING, ContextLinks.PLUGIN_ID, message));
    }
}
