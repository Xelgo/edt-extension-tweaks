package ru.xelgo.edt.contextlinks.ui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.e1c.g5.dt.applications.wst.IServerApplication;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Configures projects that should be skipped during infobase application update.
 */
public class ConfigureApplicationUpdateProjectsHandler
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
        throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        IApplication application = getSelectedApplication(event);
        if (application == null)
        {
            Messages.showInfo(shell, Messages.ConfigureApplicationUpdateProjectsHandler_Title,
                Messages.ConfigureApplicationUpdateProjectsHandler_NoApplication);
            return null;
        }

        InfobaseReference infobase = getInfobase(application);
        if (infobase == null || infobase.getUuid() == null)
        {
            Messages.showInfo(shell, Messages.ConfigureApplicationUpdateProjectsHandler_Title,
                Messages.ConfigureApplicationUpdateProjectsHandler_NoInfobase);
            return null;
        }

        IProject applicationProject = application.getProject();
        IProject[] candidates = ContextLinks.getApplicationUpdateCandidateProjects(applicationProject);
        if (candidates.length == 0)
        {
            Messages.showInfo(shell, Messages.ConfigureApplicationUpdateProjectsHandler_Title,
                Messages.ConfigureApplicationUpdateProjectsHandler_NoCandidates);
            return null;
        }

        Object[] initialSelection = getInitialSelection(applicationProject, infobase, candidates);
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
            .message(Messages.ConfigureApplicationUpdateProjectsHandler_Message)
            .preselect(initialSelection)
            .title(Messages.ConfigureApplicationUpdateProjectsHandler_Title)
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
            ContextLinks.setDisabledApplicationUpdateProjectNames(applicationProject, infobase, names);
        }
        catch (CoreException e)
        {
            throw new ExecutionException("Failed to save EDT application update project settings", e); //$NON-NLS-1$
        }
        return null;
    }

    private IApplication getSelectedApplication(ExecutionEvent event)
    {
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection && selection.size() == 1)
        {
            Object selected = selection.getFirstElement();
            if (selected instanceof IInfobaseApplication || selected instanceof IServerApplication)
                return (IApplication)selected;
        }
        return null;
    }

    private static InfobaseReference getInfobase(IApplication application)
    {
        if (application instanceof IInfobaseApplication infobaseApplication)
            return infobaseApplication.getInfobase();
        return Adapters.adapt(application, InfobaseReference.class);
    }

    private Object[] getInitialSelection(IProject applicationProject, InfobaseReference infobase, IProject[] candidates)
    {
        Set<String> selectedNames = ContextLinks.getDisabledApplicationUpdateProjectNames(applicationProject, infobase);
        return Arrays.stream(candidates)
            .filter(candidate -> selectedNames.contains(candidate.getName()))
            .toArray();
    }
}
