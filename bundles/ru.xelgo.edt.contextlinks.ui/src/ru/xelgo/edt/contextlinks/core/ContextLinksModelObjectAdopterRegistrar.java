package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Guards Query Wizard adoption from trying to copy objects owned by another extension.
 */
public final class ContextLinksModelObjectAdopterRegistrar
{
    private static final String SERVICE_CLASS_NAME =
        "com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter"; //$NON-NLS-1$
    private static final String DISABLE_PROPERTY = ContextLinks.PLUGIN_ID + ".modelObjectAdopter.disable"; //$NON-NLS-1$
    private static final String WRAPPER_MARKER_PROPERTY = ContextLinks.PLUGIN_ID + ".modelObjectAdopter.wrapper"; //$NON-NLS-1$
    private static final Set<String> loggedRegistrationStates = ConcurrentHashMap.newKeySet();
    private static ServiceRegistration<?> registration;

    private ContextLinksModelObjectAdopterRegistrar()
    {
        // Utility class.
    }

    public static synchronized void ensureRegistered()
    {
        if (registration != null)
            return;

        if (Boolean.getBoolean(DISABLE_PROPERTY))
        {
            logRegistrationState("EDT Context Links model object adopter wrapper disabled by system property"); //$NON-NLS-1$
            return;
        }

        Bundle bundle = FrameworkUtil.getBundle(ContextLinksModelObjectAdopterRegistrar.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context == null)
        {
            logRegistrationState("EDT Context Links model object adopter wrapper not registered: bundle context is null"); //$NON-NLS-1$
            return;
        }

        Object proxy = ContextLinksModelObjectAdopterProxy.create(context);
        if (proxy == null)
        {
            logRegistrationState("EDT Context Links model object adopter wrapper not registered: proxy creation failed"); //$NON-NLS-1$
            return;
        }

        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, Integer.valueOf(1000));
        properties.put(WRAPPER_MARKER_PROPERTY, Boolean.TRUE.toString());
        registration = context.registerService(SERVICE_CLASS_NAME, proxy, properties);
        ContextLinks.logWarning("EDT Context Links model object adopter wrapper registered"); //$NON-NLS-1$
    }

    private static void logRegistrationState(String message)
    {
        if (loggedRegistrationStates.add(message))
            ContextLinks.logWarning(message);
    }

    private static final class ContextLinksModelObjectAdopterProxy
        implements InvocationHandler
    {
        private static final Set<String> loggedSkippedObjects = ConcurrentHashMap.newKeySet();
        private static final Set<String> loggedDelegateFailures = ConcurrentHashMap.newKeySet();
        private final BundleContext context;

        private ContextLinksModelObjectAdopterProxy(BundleContext context)
        {
            this.context = context;
        }

        static Object create(BundleContext context)
        {
            try
            {
                Class<?> serviceClass = loadServiceClass(context);
                return Proxy.newProxyInstance(serviceClass.getClassLoader(), new Class<?>[] { serviceClass },
                    new ContextLinksModelObjectAdopterProxy(context));
            }
            catch (ReflectiveOperationException | IllegalArgumentException e)
            {
                ContextLinks.logError("EDT Context Links failed to create model object adopter wrapper", e); //$NON-NLS-1$
                return null;
            }
        }

        private static Class<?> loadServiceClass(BundleContext context)
            throws ClassNotFoundException
        {
            for (Bundle bundle : context.getBundles())
            {
                if ("com._1c.g5.v8.dt.md.extension".equals(bundle.getSymbolicName())) //$NON-NLS-1$
                    return bundle.loadClass(SERVICE_CLASS_NAME);
            }
            return Class.forName(SERVICE_CLASS_NAME);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
                return invokeObjectMethod(proxy, method, args);

            if (isIsAdoptedMethod(method, args)
                && isForeignExtensionObjectInQueryWizard((EObject)args[0], (IExtensionProject)args[1], "isAdopted")) //$NON-NLS-1$
                return Boolean.TRUE;

            Object[] delegateArgs = args;
            if (isAdoptAndAttachListMethod(method, args))
            {
                List<?> filteredObjects = filterForeignExtensionObjects((List<?>)args[0], (IExtensionProject)args[1]);
                if (filteredObjects != args[0])
                {
                    if (filteredObjects.isEmpty())
                        return Collections.emptyList();

                    delegateArgs = args.clone();
                    delegateArgs[0] = filteredObjects;
                }
            }
            else if (isAdoptAndAttachObjectMethod(method, args)
                && isForeignExtensionObjectInQueryWizard((EObject)args[0], (IExtensionProject)args[1], "adoptAndAttach")) //$NON-NLS-1$
            {
                return args[0];
            }

            DelegateService delegate = findDelegate(context);
            if (delegate == null)
                return fallbackValue(method);

            try
            {
                return invokeDelegate(delegate.service, method, delegateArgs);
            }
            finally
            {
                delegate.close(context);
            }
        }

        private static boolean isIsAdoptedMethod(Method method, Object[] args)
        {
            return "isAdopted".equals(method.getName()) && args != null && args.length == 2 //$NON-NLS-1$
                && args[0] instanceof EObject && args[1] instanceof IExtensionProject;
        }

        private static boolean isAdoptAndAttachObjectMethod(Method method, Object[] args)
        {
            return "adoptAndAttach".equals(method.getName()) && args != null && args.length == 3 //$NON-NLS-1$
                && args[0] instanceof EObject && args[1] instanceof IExtensionProject;
        }

        private static boolean isAdoptAndAttachListMethod(Method method, Object[] args)
        {
            return "adoptAndAttach".equals(method.getName()) && args != null && args.length == 3 //$NON-NLS-1$
                && args[0] instanceof List<?> && args[1] instanceof IExtensionProject;
        }

        private static List<?> filterForeignExtensionObjects(List<?> objects, IExtensionProject targetProject)
        {
            List<Object> result = null;
            for (int i = 0; i < objects.size(); i++)
            {
                Object object = objects.get(i);
                if (object instanceof EObject
                    && isForeignExtensionObjectInQueryWizard((EObject)object, targetProject, "adoptAndAttach.list")) //$NON-NLS-1$
                {
                    if (result == null)
                        result = new ArrayList<>(objects.subList(0, i));
                    continue;
                }

                if (result != null)
                    result.add(object);
            }
            return result != null ? result : objects;
        }

        private static boolean isForeignExtensionObjectInQueryWizard(EObject object, IExtensionProject targetProject,
            String phase)
        {
            if (object == null || targetProject == null || !isQueryWizardAdoptSupportStack())
                return false;

            IV8Project ownerProject = ownerProject(object);
            if (!(ownerProject instanceof IExtensionProject) || sameProject(ownerProject, targetProject))
                return false;

            logSkippedObject(phase, object, ownerProject, targetProject);
            return true;
        }

        private static IV8Project ownerProject(EObject object)
        {
            IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
            if (projectManager == null)
                return null;

            try
            {
                return projectManager.getProject(object);
            }
            catch (RuntimeException e)
            {
                if (loggedDelegateFailures.add("owner|" + describeEObject(object))) //$NON-NLS-1$
                    ContextLinks.logError("EDT Context Links failed to resolve owner project for adoption object", e); //$NON-NLS-1$
                return null;
            }
        }

        private static boolean sameProject(IV8Project ownerProject, IExtensionProject targetProject)
        {
            if (ownerProject == targetProject)
                return true;

            try
            {
                return Objects.equals(ownerProject.getProject(), targetProject.getProject());
            }
            catch (RuntimeException e)
            {
                return false;
            }
        }

        private static boolean isQueryWizardAdoptSupportStack()
        {
            for (StackTraceElement element : Thread.currentThread().getStackTrace())
            {
                if (element.getClassName().endsWith("QueryWizardAdoptSupport")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        private static void logSkippedObject(String phase, EObject object, IV8Project ownerProject,
            IExtensionProject targetProject)
        {
            String key = phase + "|" + describeProject(ownerProject) + "->" + describeProject(targetProject) //$NON-NLS-1$ //$NON-NLS-2$
                + "|" + describeEObject(object); //$NON-NLS-1$
            if (loggedSkippedObjects.add(key))
            {
                ContextLinks.logWarning("EDT Context Links adoption.skipForeignExtension phase=" + phase //$NON-NLS-1$
                    + " object=" + describeEObject(object) //$NON-NLS-1$
                    + " owner=" + describeProject(ownerProject) //$NON-NLS-1$
                    + " target=" + describeProject(targetProject)); //$NON-NLS-1$
            }
        }

        private static String describeProject(IV8Project project)
        {
            if (project == null)
                return "NULL"; //$NON-NLS-1$

            try
            {
                return project.getProject() != null ? project.getProject().getName() : project.getClass().getName();
            }
            catch (RuntimeException e)
            {
                return project.getClass().getName();
            }
        }

        private static String describeEObject(EObject object)
        {
            if (object == null)
                return "NULL"; //$NON-NLS-1$

            String className = object.eClass() != null ? object.eClass().getName() : object.getClass().getName();
            return className + "@" + String.valueOf(EcoreUtil.getURI(object)); //$NON-NLS-1$
        }

        private static Object invokeDelegate(Object delegate, Method method, Object[] args)
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

        private static Object invokeObjectMethod(Object proxy, Method method, Object[] args)
        {
            switch (method.getName())
            {
            case "toString": //$NON-NLS-1$
                return ContextLinksModelObjectAdopterProxy.class.getName();
            case "hashCode": //$NON-NLS-1$
                return Integer.valueOf(System.identityHashCode(proxy));
            case "equals": //$NON-NLS-1$
                return Boolean.valueOf(proxy == (args != null && args.length == 1 ? args[0] : null));
            default:
                throw new IllegalArgumentException("Unsupported Object method: " + method.getName()); //$NON-NLS-1$
            }
        }

        private static Object fallbackValue(Method method)
        {
            Class<?> returnType = method.getReturnType();
            if (Boolean.TYPE == returnType)
                return Boolean.FALSE;
            if (List.class.isAssignableFrom(returnType))
                return Collections.emptyList();
            return null;
        }

        private static DelegateService findDelegate(BundleContext context)
        {
            try
            {
                ServiceReference<?>[] references = context.getServiceReferences(SERVICE_CLASS_NAME, null);
                if (references == null || references.length == 0)
                    return null;

                return java.util.Arrays.stream(references)
                    .filter(reference -> reference.getProperty(WRAPPER_MARKER_PROPERTY) == null)
                    .sorted(Comparator.comparingInt(ContextLinksModelObjectAdopterProxy::serviceRanking).reversed()
                        .thenComparingLong(ContextLinksModelObjectAdopterProxy::serviceId))
                    .map(reference -> getDelegateService(context, reference))
                    .filter(service -> service != null)
                    .findFirst()
                    .orElse(null);
            }
            catch (InvalidSyntaxException e)
            {
                ContextLinks.logError("EDT Context Links failed to find delegate IModelObjectAdopter", e); //$NON-NLS-1$
                return null;
            }
        }

        private static DelegateService getDelegateService(BundleContext context, ServiceReference<?> reference)
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

        void close(BundleContext context)
        {
            context.ungetService(reference);
        }
    }
}
