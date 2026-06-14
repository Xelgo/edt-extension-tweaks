package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;

/**
 * Softly masks disabled projects as synchronized for EDT infobase update flows.
 */
final class ContextLinksInfobaseSynchronizationManagerProxy
    implements InvocationHandler
{
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
        Object disabledValue = disabledValue(method, project);
        if (disabledValue != null)
            return disabledValue;

        DelegateService delegate = delegate();
        try
        {
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
            return null;

        String name = method.getName();
        Class<?> returnType = method.getReturnType();
        if ("getEqualityState".equals(name)) //$NON-NLS-1$
            return InfobaseEqualityState.EQUAL;
        if ("isConnected".equals(name)) //$NON-NLS-1$
            return Boolean.TRUE;
        if ("updateInfobase".equals(name) || "reloadInfobase".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.TRUE;
        if ("retrieveInfobaseChanges".equals(name)) //$NON-NLS-1$
            return InfobaseChangesResolutionResult.NO_CHANGES;
        if ("updateAllInfobases".equals(name)) //$NON-NLS-1$
            return Map.of();
        if (returnType == Void.TYPE)
            return null;
        return null;
    }

    private boolean isDisabled(IProject updateProject, String operation)
    {
        IProject applicationProject = ContextLinks.getApplicationProject(updateProject);
        if (!ContextLinks.isApplicationUpdateDisabled(applicationProject, updateProject))
            return false;

        ContextLinks.logDebug("EDT Context Links DEBUG [application.update.skip] operation=" + operation //$NON-NLS-1$
            + " applicationProject=" + applicationProject.getName() + " updateProject=" + updateProject.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
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
            ContextLinks.logError("EDT Context Links failed to find delegate IInfobaseSynchronizationManager", e); //$NON-NLS-1$
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
