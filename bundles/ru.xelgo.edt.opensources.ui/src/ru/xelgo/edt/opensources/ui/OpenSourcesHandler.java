package ru.xelgo.edt.opensources.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.ISetSelectionTarget;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Opens the source folder for a selected EDT metadata object in Project Explorer.
 */
public class OpenSourcesHandler
    extends AbstractHandler
{
    private static final String PROJECT_EXPLORER_VIEW_ID = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IResource resource = getSelectedResource(event);
        if (resource == null)
        {
            showInfo(event, Messages.OpenSourcesHandler_NoResource);
            return null;
        }

        IResource target = getFolderToReveal(resource);
        if (target == null || !target.exists())
        {
            showInfo(event, Messages.OpenSourcesHandler_NoResource);
            return null;
        }

        reveal(event, target);
        return null;
    }

    private IResource getSelectedResource(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection))
            return null;

        IResourceLookup resourceLookup = ServiceAccess.get(IResourceLookup.class);
        if (resourceLookup == null)
            return null;

        Iterator<?> iterator = ((IStructuredSelection)selection).iterator();
        while (iterator.hasNext())
        {
            IResource resource = getResource(iterator.next(), resourceLookup);
            if (resource != null)
                return resource;
        }
        return null;
    }

    private IResource getResource(Object selected, IResourceLookup resourceLookup)
    {
        IResource resource = adapt(selected, IResource.class);
        if (resource != null)
            return resource;

        EObject modelObject = getModelObject(selected);
        if (modelObject == null)
            return null;

        if (modelObject instanceof BasicForm)
        {
            IResource formFolder = getFormFolder((BasicForm)modelObject, resourceLookup);
            if (formFolder != null)
                return formFolder;
        }

        IFile file = resourceLookup.getPlatformResource(modelObject);
        if (file != null)
            return file;
        if (modelObject.eResource() != null)
            return resourceLookup.getPlatformResource(modelObject.eResource());

        return null;
    }

    private IResource getFormFolder(BasicForm form, IResourceLookup resourceLookup)
    {
        IFile formFile = resourceLookup.getPlatformResource(form);
        IResource directFolder = getExistingParentFolder(formFile);
        if (directFolder != null && isFormFolder(directFolder))
            return directFolder;

        EObject owner = form.eContainer();
        while (owner != null)
        {
            IFile ownerFile = resourceLookup.getPlatformResource(owner);
            IContainer ownerFolder = ownerFile != null ? ownerFile.getParent() : null;
            if (ownerFolder != null)
            {
                IFolder formFolder = ownerFolder.getFolder(new Path("Forms/" + form.getName())); //$NON-NLS-1$
                if (formFolder.exists())
                    return formFolder;
            }
            owner = owner.eContainer();
        }

        return directFolder;
    }

    private IResource getExistingParentFolder(IFile file)
    {
        if (file == null)
            return null;

        IContainer parent = file.getParent();
        return parent != null && parent.exists() ? parent : null;
    }

    private boolean isFormFolder(IResource resource)
    {
        IContainer parent = resource.getParent();
        return parent != null && "Forms".equals(parent.getName()); //$NON-NLS-1$
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

    private IResource getFolderToReveal(IResource resource)
    {
        if (resource instanceof IContainer)
            return resource;

        IContainer parent = resource.getParent();
        return parent != null ? parent : resource;
    }

    private void reveal(ExecutionEvent event, IResource resource)
        throws ExecutionException
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
            window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return;

        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;

        IWorkbenchPart navigator = showView(page, PROJECT_EXPLORER_VIEW_ID);

        ISetSelectionTarget selectionTarget = navigator != null ? navigator.getAdapter(ISetSelectionTarget.class) : null;
        if (selectionTarget != null)
            selectionTarget.selectReveal(new StructuredSelection(resource));
    }

    private IWorkbenchPart showView(IWorkbenchPage page, String viewId)
        throws ExecutionException
    {
        try
        {
            return page.showView(viewId);
        }
        catch (PartInitException e)
        {
            return null;
        }
    }

    private void showInfo(ExecutionEvent event, String message)
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        MessageDialog.openInformation(shell, Messages.OpenSourcesHandler_Title, message);
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
}
