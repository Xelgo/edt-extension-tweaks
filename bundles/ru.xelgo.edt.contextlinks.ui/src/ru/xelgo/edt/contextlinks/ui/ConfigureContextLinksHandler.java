package ru.xelgo.edt.contextlinks.ui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
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
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.execute.enter] event=" + event); //$NON-NLS-1$
        IProject project = getSelectedProject(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null)
        {
            ContextLinks.logWarning("EDT Context Links configure command cannot resolve selected project"); //$NON-NLS-1$
            Messages.showInfo(shell, Messages.ConfigureContextLinksHandler_Title,
                Messages.ConfigureContextLinksHandler_NoProject);
            return null;
        }
        ContextLinks.logDebug("EDT Context Links configure command selected project " + project.getName()); //$NON-NLS-1$
        if (!ContextLinks.isContextConfigurableProject(project))
        {
            ContextLinks.logWarning("EDT Context Links configure command ignored non-configurable project " //$NON-NLS-1$
                + project.getName());
            Messages.showInfo(shell, Messages.ConfigureContextLinksHandler_Title,
                Messages.ConfigureContextLinksHandler_NotExtensionProject);
            return null;
        }

        IProject[] candidates = Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(IProject::isAccessible)
            .filter(candidate -> !candidate.equals(project))
            .filter(ContextLinks::isExtensionProject)
            .toArray(IProject[]::new);
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.candidates] project=" + project.getName() //$NON-NLS-1$
            + " candidates=" + Arrays.toString(Arrays.stream(candidates).map(IProject::getName).toArray())); //$NON-NLS-1$

        Object[] initialSelection = getInitialSelection(project, candidates);
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.initialSelection] project=" + project.getName() //$NON-NLS-1$
            + " selected=" + Arrays.toString(Arrays.stream(initialSelection).map(this::describeObject).toArray())); //$NON-NLS-1$

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
            .preselect(initialSelection)
            .title(Messages.ConfigureContextLinksHandler_Title)
            .create(shell);

        if (dialog.open() != Window.OK)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [ui.dialog.cancel] project=" + project.getName()); //$NON-NLS-1$
            return null;
        }

        Set<String> names = new LinkedHashSet<>();
        for (Object result : dialog.getResult())
        {
            if (result instanceof IProject)
                names.add(((IProject)result).getName());
            else
                ContextLinks.logDebug("EDT Context Links DEBUG [ui.dialog.unexpectedResult] value=" //$NON-NLS-1$
                    + describeObject(result));
        }
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.dialog.ok] project=" + project.getName() + " names=" + names); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            ContextLinks.setContextProjectNames(project, names);
            ContextLinks.logWarning("EDT Context Links configure command saved project=" + project.getName() //$NON-NLS-1$
                + ", names=" + names + ", allSettings=" + ContextLinks.describeWorkspaceSettings()); //$NON-NLS-1$ //$NON-NLS-2$
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
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.selection] selection=" + describeObject(selection)); //$NON-NLS-1$
        Object selected = selection instanceof IStructuredSelection
            ? ((IStructuredSelection)selection).getFirstElement()
            : null;
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.selection.first] selected=" + describeObject(selected)); //$NON-NLS-1$
        IProject project = getProject(selected);
        if (project != null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [ui.selection.project] project=" + project.getName()); //$NON-NLS-1$
            return project;
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [ui.selection.fallbackEditor]"); //$NON-NLS-1$
        return getActiveEditorProject(event);
    }

    private IProject getProject(Object selected)
    {
        IProject project = ProjectSelection.getProject(selected);
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.getProject] selected=" + describeObject(selected) //$NON-NLS-1$
            + " project=" + (project != null ? project.getName() : "NULL")); //$NON-NLS-1$ //$NON-NLS-2$
        return project;
    }

    private IProject getActiveEditorProject(ExecutionEvent event)
    {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.editor.active] editor=" + describeObject(editor)); //$NON-NLS-1$
        if (editor != null)
        {
            IProject project = getProject(editor.getEditorInput());
            if (project != null)
            {
                ContextLinks.logDebug("EDT Context Links DEBUG [ui.editor.project] via=HandlerUtil project=" //$NON-NLS-1$
                    + project.getName());
                return project;
            }
        }

        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        IEditorPart activeEditor = page != null ? page.getActiveEditor() : null;
        if (activeEditor == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [ui.editor.project] activeEditor=NULL"); //$NON-NLS-1$
            return null;
        }

        IEditorInput input = activeEditor.getEditorInput();
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.editor.input] input=" + describeObject(input)); //$NON-NLS-1$
        return getProject(input);
    }

    private String describeObject(Object object)
    {
        if (object == null)
            return "NULL"; //$NON-NLS-1$
        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object)) //$NON-NLS-1$
            + "(" + object + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
