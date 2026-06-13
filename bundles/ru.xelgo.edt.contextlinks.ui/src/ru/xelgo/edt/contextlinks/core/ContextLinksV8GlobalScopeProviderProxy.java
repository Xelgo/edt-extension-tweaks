package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com._1c.g5.modeling.xtext.scoping.CompositeScope;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
import com.google.common.base.Predicate;

/**
 * Reflection-based proxy for EDT's non-API IV8GlobalScopeProvider service.
 */
final class ContextLinksV8GlobalScopeProviderProxy
    implements InvocationHandler
{
    static final String SERVICE_CLASS_NAME = "com._1c.g5.v8.dt.core.scoping.IV8GlobalScopeProvider"; //$NON-NLS-1$
    static final String WRAPPER_MARKER_PROPERTY = ContextLinks.PLUGIN_ID + ".qlV8Scope.wrapper"; //$NON-NLS-1$

    private static final Set<String> loggedCompositions = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedDelegateFailures = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedResourceCalls = ConcurrentHashMap.newKeySet();

    private final org.osgi.framework.BundleContext context;
    private final Method projectScopeMethod;

    private ContextLinksV8GlobalScopeProviderProxy(org.osgi.framework.BundleContext context, Class<?> serviceClass)
        throws NoSuchMethodException
    {
        this.context = context;
        this.projectScopeMethod = findProjectScopeMethod(serviceClass);
    }

    static Object create(org.osgi.framework.BundleContext context)
    {
        try
        {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS_NAME, false,
                ContextLinksV8GlobalScopeProviderProxy.class.getClassLoader());
            ContextLinksV8GlobalScopeProviderProxy handler =
                new ContextLinksV8GlobalScopeProviderProxy(context, serviceClass);
            return Proxy.newProxyInstance(serviceClass.getClassLoader(), new Class<?>[] { serviceClass }, handler);
        }
        catch (ReflectiveOperationException | IllegalArgumentException e)
        {
            ContextLinks.logError("EDT Context Links failed to create QL BM global scope wrapper", e); //$NON-NLS-1$
            return null;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable
    {
        if (method.getDeclaringClass() == Object.class)
            return method.invoke(this, args);

        DelegateService delegate = findDelegate(context);
        if (delegate == null)
            return fallbackValue(method);

        try
        {
            Object value = invokeDelegate(delegate.service, method, args);
            if (!isResourceScopeMethod(method, args))
                return value;

            Resource resource = (Resource)args[0];
            IProject project = workspaceProject(resource);
            logResourceCall(resource, project);
            if (!shouldExtendQlScope(resource, project))
                return value;

            @SuppressWarnings("unchecked")
            Predicate<IEObjectDescription> filter = (Predicate<IEObjectDescription>)args[2];
            return composeLinkedExtensionScope(delegate.service, project, (EReference)args[1], filter, (IScope)value);
        }
        finally
        {
            delegate.close(context);
        }
    }

    private Object composeLinkedExtensionScope(Object delegate, IProject project, EReference reference,
        Predicate<IEObjectDescription> filter, IScope ownScope)
    {
        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        if (linkedProjectNames.isEmpty())
            return ownScope;

        CompositeScope result = new CompositeScope(ownScope != null ? ownScope : ISlicedScope.NULLSCOPE, true);
        List<String> addedProjects = new ArrayList<>();
        List<String> skippedProjects = new ArrayList<>();

        for (String linkedProjectName : linkedProjectNames)
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            if (!isUsableLinkedExtension(project, linkedProject))
            {
                skippedProjects.add(linkedProjectName);
                continue;
            }

            IScope linkedScope = getLinkedProjectScope(delegate, linkedProject, reference, filter);
            if (linkedScope == null)
            {
                skippedProjects.add(linkedProjectName);
                continue;
            }

            result.addScope(linkedScope);
            addedProjects.add(linkedProjectName);
        }

        logComposition(project, addedProjects, skippedProjects);
        return addedProjects.isEmpty() ? ownScope : result;
    }

    private IScope getLinkedProjectScope(Object delegate, IProject linkedProject, EReference reference,
        Predicate<IEObjectDescription> filter)
    {
        try
        {
            return (IScope)projectScopeMethod.invoke(delegate, linkedProject, reference, filter);
        }
        catch (IllegalAccessException | InvocationTargetException | RuntimeException e)
        {
            if (loggedDelegateFailures.add(linkedProject.getName()))
                ContextLinks.logError("EDT Context Links failed to get linked QL BM scope for " + linkedProject.getName(), e); //$NON-NLS-1$
            return null;
        }
    }

    private boolean isResourceScopeMethod(Method method, Object[] args)
    {
        return "getScope".equals(method.getName()) && args != null && args.length == 3 && args[0] instanceof Resource; //$NON-NLS-1$
    }

    private boolean shouldExtendQlScope(Resource resource, IProject project)
    {
        return isQlResource(resource) && ContextLinks.isExtensionProject(project)
            && !ContextLinks.getContextProjectNames(project).isEmpty();
    }

    private boolean isQlResource(Resource resource)
    {
        return resource != null && resource.getClass().getName().startsWith("com._1c.g5.v8.dt.ql."); //$NON-NLS-1$
    }

    private void logResourceCall(Resource resource, IProject project)
    {
        String projectName = project != null ? project.getName() : "NULL"; //$NON-NLS-1$
        String key = resource.getClass().getName() + "|" + projectName; //$NON-NLS-1$
        if (loggedResourceCalls.add(key))
        {
            ContextLinks.logWarning("EDT Context Links QL BM provider call resource=" //$NON-NLS-1$
                + resource.getClass().getName() + " project=" + projectName //$NON-NLS-1$
                + " ql=" + isQlResource(resource)); //$NON-NLS-1$
        }
    }

    private IProject workspaceProject(Resource resource)
    {
        return resource != null ? ContextLinks.getProject(resource.getURI()) : null;
    }

    private boolean isUsableLinkedExtension(IProject project, IProject linkedProject)
    {
        return linkedProject != null && linkedProject.isAccessible() && !linkedProject.equals(project)
            && ContextLinks.isExtensionProject(linkedProject);
    }

    private void logComposition(IProject project, List<String> addedProjects, List<String> skippedProjects)
    {
        String key = project.getName() + "|" + addedProjects + "|" + skippedProjects; //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedCompositions.add(key))
        {
            ContextLinks.logWarning("EDT Context Links QL BM scope project=" + project.getName() //$NON-NLS-1$
                + " linked=" + addedProjects + " skipped=" + skippedProjects); //$NON-NLS-1$ //$NON-NLS-2$
        }
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
            throw e.getCause();
        }
    }

    private Object fallbackValue(Method method)
    {
        if (org.eclipse.xtext.resource.IResourceDescriptions.class.getName().equals(method.getReturnType().getName()))
            return new org.eclipse.xtext.resource.IResourceDescriptions.NullImpl();
        if (IScope.class.getName().equals(method.getReturnType().getName()))
            return ISlicedScope.NULLSCOPE;
        return null;
    }

    private static Method findProjectScopeMethod(Class<?> serviceClass)
        throws NoSuchMethodException
    {
        for (Method method : serviceClass.getMethods())
        {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if ("getScope".equals(method.getName()) && parameterTypes.length == 3 //$NON-NLS-1$
                && IProject.class.isAssignableFrom(parameterTypes[0]))
            {
                return method;
            }
        }
        throw new NoSuchMethodException("getScope(IProject, EReference, Predicate)"); //$NON-NLS-1$
    }

    private static DelegateService findDelegate(org.osgi.framework.BundleContext context)
    {
        try
        {
            ServiceReference<?>[] references = context.getServiceReferences(SERVICE_CLASS_NAME, null);
            if (references == null || references.length == 0)
                return null;

            return java.util.Arrays.stream(references)
                .filter(reference -> reference.getProperty(WRAPPER_MARKER_PROPERTY) == null)
                .sorted(Comparator.comparingInt(ContextLinksV8GlobalScopeProviderProxy::serviceRanking).reversed()
                    .thenComparingLong(ContextLinksV8GlobalScopeProviderProxy::serviceId))
                .map(reference -> getDelegateService(context, reference))
                .filter(service -> service != null)
                .findFirst()
                .orElse(null);
        }
        catch (InvalidSyntaxException e)
        {
            ContextLinks.logError("EDT Context Links failed to find delegate IV8GlobalScopeProvider", e); //$NON-NLS-1$
            return null;
        }
    }

    private static DelegateService getDelegateService(org.osgi.framework.BundleContext context,
        ServiceReference<?> reference)
    {
        Object service = context.getService(reference);
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
        private final ServiceReference<?> reference;
        private final Object service;

        DelegateService(ServiceReference<?> reference, Object service)
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
