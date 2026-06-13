package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.wiring.ServiceAccess;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

final class ProjectSelection
{
    private ProjectSelection()
    {
        // Utility class.
    }

    static IProject getProject(Object selected)
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

    static boolean isExtensionProject(Object selected)
    {
        IProject project = getProject(selected);
        boolean result = ContextLinks.isExtensionProject(project);
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.visible.extension] selected=" + describeObject(selected) //$NON-NLS-1$
            + " project=" + (project != null ? project.getName() : "NULL") //$NON-NLS-1$ //$NON-NLS-2$
            + " result=" + result); //$NON-NLS-1$
        return result;
    }

    static boolean isContextConfigurableProject(Object selected)
    {
        IProject project = getProject(selected);
        boolean result = ContextLinks.isContextConfigurableProject(project);
        ContextLinks.logDebug("EDT Context Links DEBUG [ui.visible.configurable] selected=" + describeObject(selected) //$NON-NLS-1$
            + " project=" + (project != null ? project.getName() : "NULL") //$NON-NLS-1$ //$NON-NLS-2$
            + " result=" + result); //$NON-NLS-1$
        return result;
    }

    private static EObject getModelObject(Object selected)
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

    private static Object invokeNoArg(Object target, String methodName)
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

    private static <T> T adapt(Object object, Class<T> type)
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

    private static String describeObject(Object object)
    {
        if (object == null)
            return "NULL"; //$NON-NLS-1$
        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object)); //$NON-NLS-1$
    }
}
