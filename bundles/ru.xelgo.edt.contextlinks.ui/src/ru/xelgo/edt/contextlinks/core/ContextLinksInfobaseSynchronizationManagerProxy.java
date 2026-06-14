package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;

/**
 * Softly masks disabled projects as synchronized for EDT infobase update flows.
 */
final class ContextLinksInfobaseSynchronizationManagerProxy
    implements InvocationHandler
{
    private static final Object NOT_DISABLED = new Object();
    private static final Object SKIPPED_VOID = new Object();

    private final org.osgi.framework.BundleContext context;

    private ContextLinksInfobaseSynchronizationManagerProxy(org.osgi.framework.BundleContext context)
    {
        this.context = context;
    }

    static IInfobaseSynchronizationManager create(org.osgi.framework.BundleContext context)
    {
        Object proxy = Proxy.newProxyInstance(IInfobaseSynchronizationManager.class.getClassLoader(),
            new Class<?>[] { IInfobaseSynchronizationManager.class },
            new ContextLinksInfobaseSynchronizationManagerProxy(context));
        return (IInfobaseSynchronizationManager)proxy;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable
    {
        if (method.getDeclaringClass() == Object.class)
            return method.invoke(this, args);

        IProject project = firstProject(args);
        DelegateService delegate = delegate();
        try
        {
            if ("updateAllInfobases".equals(method.getName()) && shouldFilterUpdateAll(project)) //$NON-NLS-1$
                return updateAllInfobases(delegate.service, ContextLinks.getApplicationProject(project), project, args);
            if (isUpdateOperation(method.getName()) && isDisabled(project, method.getName()))
                return updateEnabledDependentInfobases(delegate.service, method, project, args);

            Object disabledValue = disabledValue(method, project);
            if (disabledValue == SKIPPED_VOID)
                return null;
            if (disabledValue != NOT_DISABLED)
                return disabledValue;

            return invokeDelegate(delegate.service, method, args);
        }
        finally
        {
            delegate.close(context);
        }
    }

    private Object disabledValue(Method method, IProject updateProject)
    {
        if (updateProject == null || !isDisabled(updateProject, method.getName()))
            return NOT_DISABLED;

        String name = method.getName();
        if ("getEqualityState".equals(name)) //$NON-NLS-1$
            return InfobaseEqualityState.EQUAL;
        if ("isConnected".equals(name)) //$NON-NLS-1$
            return Boolean.TRUE;
        if ("updateInfobase".equals(name) || "reloadInfobase".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            return successfulUpdateResult(method.getReturnType(), true);
        if ("retrieveInfobaseChanges".equals(name)) //$NON-NLS-1$
            return noInfobaseChangesResult(method.getReturnType());
        if ("connectInfobase".equals(name) || "reconnectIfConnected".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            return SKIPPED_VOID;
        return NOT_DISABLED;
    }

    private boolean isDisabled(IProject updateProject, String operation)
    {
        IProject applicationProject = ContextLinks.getApplicationProject(updateProject);
        if (!ContextLinks.isApplicationUpdateDisabled(applicationProject, updateProject))
            return false;

        ContextLinks.logDebug("EDT Extension Tweaks DEBUG [application.update.skip] operation=" + operation //$NON-NLS-1$
            + " applicationProject=" + applicationProject.getName() + " updateProject=" + updateProject.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    private boolean isUpdateOperation(String operation)
    {
        return "updateInfobase".equals(operation) || "reloadInfobase".equals(operation); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Object updateEnabledDependentInfobases(Object delegate, Method publicMethod, IProject project, Object[] args)
        throws Throwable
    {
        String operation = publicMethod.getName();
        IProject applicationProject = ContextLinks.getApplicationProject(project);
        Object infobase = args != null && args.length > 1 ? args[1] : null;
        Object callback = args != null && args.length > 2 ? args[2] : null;
        Object monitor = args != null && args.length > 4 ? args[4] : null;
        boolean reload = "reloadInfobase".equals(operation); //$NON-NLS-1$
        boolean result = true;
        boolean hasDependentProjects = false;
        for (IProject dependentProject : getDependentExtensionProjects(delegate, project))
        {
            hasDependentProjects = true;
            if (ContextLinks.isApplicationUpdateDisabled(applicationProject, dependentProject))
            {
                ContextLinks.logDebug("EDT Extension Tweaks DEBUG [application.update.skip] operation=" //$NON-NLS-1$
                    + operation + " applicationProject=" + applicationProject.getName() //$NON-NLS-1$
                    + " updateProject=" + dependentProject.getName()); //$NON-NLS-1$
                continue;
            }

            Object synchronization = getOrCreateSynchronization(delegate, dependentProject);
            invokeMethod(delegate, "synchronizeConnectionsWithApplications", 2, dependentProject, synchronization); //$NON-NLS-1$
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [application.update.route] operation=" //$NON-NLS-1$
                + operation + " fromProject=" + project.getName() + " updateProject=" //$NON-NLS-1$ //$NON-NLS-2$
                + dependentProject.getName());
            Object routedCallback = wrapUpdateCallbackForLogging(applicationProject, dependentProject, callback);
            Object dependentResult = invokeMethod(synchronization, "updateConnectedInfobase", 4, //$NON-NLS-1$
                Boolean.valueOf(reload), infobase, routedCallback, monitor);
            result &= isSuccessfulUpdateResult(dependentResult);
        }
        return successfulUpdateResult(publicMethod.getReturnType(), !hasDependentProjects || result);
    }

    private Object successfulUpdateResult(Class<?> returnType, boolean success)
    {
        if (returnType == Boolean.TYPE || returnType == Boolean.class)
            return Boolean.valueOf(success);
        if (returnType == Void.TYPE)
            return null;
        if (IStatus.class.isAssignableFrom(returnType))
            return success ? Status.OK_STATUS : Status.CANCEL_STATUS;
        return null;
    }

    private boolean isSuccessfulUpdateResult(Object result)
    {
        if (result instanceof Boolean)
            return ((Boolean)result).booleanValue();
        if (result instanceof IStatus)
            return ((IStatus)result).isOK();
        Boolean imported = invokeBooleanGetter(result, "areProjectChangesImportedToInfobase"); //$NON-NLS-1$
        if (imported != null)
            return imported.booleanValue();
        Boolean finished = invokeBooleanGetter(result, "isFinished"); //$NON-NLS-1$
        if (finished != null)
            return finished.booleanValue();
        return true;
    }

    private Boolean invokeBooleanGetter(Object target, String name)
    {
        if (target == null)
            return null;
        try
        {
            Method method = findMethod(target.getClass(), name, 0);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Boolean ? (Boolean)value : null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private Object noInfobaseChangesResult(Class<?> returnType)
    {
        if (returnType.isInstance(InfobaseChangesResolutionResult.NO_CHANGES))
            return InfobaseChangesResolutionResult.NO_CHANGES;

        Object syncResolution = createInfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES);
        return returnType.isInstance(syncResolution) ? syncResolution : null;
    }

    private Object createInfobaseSyncResolution(InfobaseChangesResolutionResult result)
    {
        try
        {
            Class<?> type = Class.forName(
                "com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution", //$NON-NLS-1$
                false, InfobaseChangesResolutionResult.class.getClassLoader());
            return type.getConstructor(InfobaseChangesResolutionResult.class)
                .newInstance(result);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private Object wrapUpdateCallbackForLogging(IProject applicationProject, IProject routedProject, Object callback)
    {
        if (!(callback instanceof IInfobaseUpdateCallback))
            return callback;

        return Proxy.newProxyInstance(IInfobaseUpdateCallback.class.getClassLoader(),
            new Class<?>[] { IInfobaseUpdateCallback.class },
            (proxy, method, args) -> {
                if ("resolveInfobaseChanges".equals(method.getName())) //$NON-NLS-1$
                    logInfobaseChanges(applicationProject, routedProject, args);
                try
                {
                    return method.invoke(callback, args);
                }
                catch (InvocationTargetException e)
                {
                    throw e.getTargetException();
                }
            });
    }

    private void logInfobaseChanges(IProject applicationProject, IProject routedProject, Object[] args)
    {
        IProject conflictProject = firstProject(args);
        IInfobaseConfigurationChange change = firstInfobaseConfigurationChange(args);
        if (change == null)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks DEBUG [application.update.conflict.inspect]" //$NON-NLS-1$
            + " applicationProject=" + applicationProject.getName() //$NON-NLS-1$
            + " routedProject=" + routedProject.getName() //$NON-NLS-1$
            + " conflictProject=" + (conflictProject == null ? "<null>" : conflictProject.getName()) //$NON-NLS-1$ //$NON-NLS-2$
            + " empty=" + change.isEmpty() //$NON-NLS-1$
            + " fullReload=" + change.isFullReloadRequired() //$NON-NLS-1$
            + " objectChanges=" + describeObjectChanges(change)); //$NON-NLS-1$
    }

    private IInfobaseConfigurationChange firstInfobaseConfigurationChange(Object[] args)
    {
        if (args == null)
            return null;
        for (Object arg : args)
        {
            if (arg instanceof IInfobaseConfigurationChange)
                return (IInfobaseConfigurationChange)arg;
        }
        return null;
    }

    private String describeObjectChanges(IInfobaseConfigurationChange change)
    {
        StringBuilder result = new StringBuilder("["); //$NON-NLS-1$
        boolean first = true;
        for (ObjectChange objectChange : change.getObjectChanges())
        {
            if (!first)
                result.append(", "); //$NON-NLS-1$
            first = false;
            result.append(objectChange.getType())
                .append(':')
                .append(objectChange.getPlatformQualifiedName());
        }
        return result.append(']').toString();
    }

    private boolean shouldFilterUpdateAll(IProject updateProject)
    {
        IProject applicationProject = ContextLinks.getApplicationProject(updateProject);
        return applicationProject != null
            && !ContextLinks.getDisabledApplicationUpdateProjectNames(applicationProject).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> updateAllInfobases(Object delegate, IProject applicationProject, IProject project,
        Object[] args)
        throws Throwable
    {
        Object callback = args != null && args.length > 1 ? args[1] : null;
        Object monitor = args != null && args.length > 2 ? args[2] : null;
        Map<Object, Object> result = new HashMap<>();

        if (!ContextLinks.isApplicationUpdateDisabled(applicationProject, project))
        {
            Object synchronization = getSynchronization(delegate, project);
            if (synchronization != null)
            {
                invokeMethod(delegate, "synchronizeConnectionsWithApplications", 2, project, synchronization); //$NON-NLS-1$
                Object updateResult = invokeMethod(synchronization, "updateAllConnectedInfobases", 2, callback, //$NON-NLS-1$
                    monitor);
                if (updateResult instanceof Map)
                    result.putAll((Map<Object, Object>)updateResult);
            }
        }
        else
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [application.update.skip] operation=updateAllInfobases" //$NON-NLS-1$
                + " applicationProject=" + applicationProject.getName() + " updateProject=" + project.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        for (IProject dependentProject : getDependentExtensionProjects(delegate, project))
            result.putAll(updateAllInfobases(delegate, applicationProject, dependentProject, args));

        return result;
    }

    @SuppressWarnings("unchecked")
    private Collection<IProject> getDependentExtensionProjects(Object delegate, IProject project)
        throws Throwable
    {
        Object result = invokeMethod(delegate, "getDependentExtensionProjects", 1, project); //$NON-NLS-1$
        return result instanceof Collection ? (Collection<IProject>)result : Set.of();
    }

    private Object getSynchronization(Object delegate, IProject project)
        throws ReflectiveOperationException
    {
        Field field = findField(delegate.getClass(), "synchronizations"); //$NON-NLS-1$
        field.setAccessible(true);
        Object synchronizations = field.get(delegate);
        return synchronizations instanceof Map ? ((Map<?, ?>)synchronizations).get(project) : null;
    }

    private Object getOrCreateSynchronization(Object delegate, IProject project)
        throws Throwable
    {
        return invokeMethod(delegate, "getOrCreateSynchronization", 1, project); //$NON-NLS-1$
    }

    private Object invokeMethod(Object target, String name, int parameterCount, Object... args)
        throws Throwable
    {
        Method method = findMethod(target.getClass(), name, parameterCount);
        method.setAccessible(true);
        try
        {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e)
        {
            throw e.getTargetException();
        }
    }

    private Method findMethod(Class<?> type, String name, int parameterCount)
        throws NoSuchMethodException
    {
        Class<?> current = type;
        while (current != null)
        {
            for (Method method : current.getDeclaredMethods())
            {
                if (name.equals(method.getName()) && method.getParameterCount() == parameterCount)
                    return method;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private Field findField(Class<?> type, String name)
        throws NoSuchFieldException
    {
        Class<?> current = type;
        while (current != null)
        {
            try
            {
                return current.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static IProject firstProject(Object[] args)
    {
        if (args == null)
            return null;
        for (Object arg : args)
        {
            if (arg instanceof IProject)
                return (IProject)arg;
        }
        return null;
    }

    private DelegateService delegate()
    {
        DelegateService delegate = findDelegate(context);
        if (delegate == null)
            throw new IllegalStateException("EDT infobase synchronization delegate service is not available"); //$NON-NLS-1$
        return delegate;
    }

    private Object invokeDelegate(Object delegate, Method method, Object[] args)
        throws Throwable
    {
        try
        {
            return method.invoke(delegate, args);
        }
        catch (InvocationTargetException e)
        {
            throw e.getTargetException();
        }
    }

    private static DelegateService findDelegate(org.osgi.framework.BundleContext context)
    {
        try
        {
            Collection<ServiceReference<IInfobaseSynchronizationManager>> references =
                context.getServiceReferences(IInfobaseSynchronizationManager.class, null);
            if (references == null || references.isEmpty())
                return null;

            return references.stream()
                .filter(reference -> reference.getProperty(
                    ContextLinksInfobaseSynchronizationManagerRegistrar.WRAPPER_MARKER_PROPERTY) == null)
                .sorted(Comparator.comparingInt(ContextLinksInfobaseSynchronizationManagerProxy::serviceRanking)
                    .reversed()
                    .thenComparingLong(ContextLinksInfobaseSynchronizationManagerProxy::serviceId))
                .map(reference -> getDelegateService(context, reference))
                .filter(service -> service != null)
                .findFirst()
                .orElse(null);
        }
        catch (InvalidSyntaxException e)
        {
            ContextLinks.logError("EDT Extension Tweaks failed to find delegate IInfobaseSynchronizationManager", e); //$NON-NLS-1$
            return null;
        }
    }

    private static DelegateService getDelegateService(org.osgi.framework.BundleContext context,
        ServiceReference<IInfobaseSynchronizationManager> reference)
    {
        IInfobaseSynchronizationManager service = context.getService(reference);
        return service != null ? new DelegateService(reference, service) : null;
    }

    private static int serviceRanking(ServiceReference<?> reference)
    {
        Object value = reference.getProperty(Constants.SERVICE_RANKING);
        return value instanceof Integer ? ((Integer)value).intValue() : 0;
    }

    private static long serviceId(ServiceReference<?> reference)
    {
        Object value = reference.getProperty(Constants.SERVICE_ID);
        return value instanceof Long ? ((Long)value).longValue() : Long.MAX_VALUE;
    }

    private static final class DelegateService
    {
        private final ServiceReference<IInfobaseSynchronizationManager> reference;
        private final IInfobaseSynchronizationManager service;

        DelegateService(ServiceReference<IInfobaseSynchronizationManager> reference,
            IInfobaseSynchronizationManager service)
        {
            this.reference = reference;
            this.service = service;
        }

        void close(org.osgi.framework.BundleContext context)
        {
            context.ungetService(reference);
        }
    }
}
