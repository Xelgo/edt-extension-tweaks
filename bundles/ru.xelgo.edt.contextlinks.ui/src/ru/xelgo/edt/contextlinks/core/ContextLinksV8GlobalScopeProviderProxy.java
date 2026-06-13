package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.scoping.IScope;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;

import com._1c.g5.modeling.xtext.scoping.CompositeScope;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.dbview.DbViewDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewElement;
import com._1c.g5.v8.dt.metadata.dbview.DbViewTableDef;
import com._1c.g5.v8.dt.metadata.dbview.Table;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.wiring.ServiceAccess;
import com.google.common.base.Predicate;

/**
 * Reflection-based proxy for EDT's non-API IV8GlobalScopeProvider service.
 */
final class ContextLinksV8GlobalScopeProviderProxy
    implements InvocationHandler
{
    static final String SERVICE_CLASS_NAME = "com._1c.g5.v8.dt.core.scoping.IV8GlobalScopeProvider"; //$NON-NLS-1$
    static final String WRAPPER_MARKER_PROPERTY = ContextLinks.PLUGIN_ID + ".qlV8Scope.wrapper"; //$NON-NLS-1$
    private static final String QL_PROBE_OBJECT =
        "\u0420\u0430\u0441\u04481_\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442"; //$NON-NLS-1$
    private static final int QL_PROBE_SCAN_LIMIT = 20000;

    private static final Set<String> loggedCompositions = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedDelegateFailures = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedProbeResults = ConcurrentHashMap.newKeySet();
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
            Class<?> serviceClass = loadServiceClass(context);
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

    private static Class<?> loadServiceClass(org.osgi.framework.BundleContext context)
        throws ClassNotFoundException
    {
        for (Bundle bundle : context.getBundles())
        {
            if ("com._1c.g5.v8.dt.core".equals(bundle.getSymbolicName())) //$NON-NLS-1$
                return bundle.loadClass(SERVICE_CLASS_NAME);
        }
        return Class.forName(SERVICE_CLASS_NAME);
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
            return composeLinkedExtensionScope(delegate.service, resource, project, (EReference)args[1], filter,
                (IScope)value);
        }
        finally
        {
            delegate.close(context);
        }
    }

    private Object composeLinkedExtensionScope(Object delegate, Resource resource, IProject project, EReference reference,
        Predicate<IEObjectDescription> filter, IScope ownScope)
    {
        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(project);
        if (linkedProjectNames.isEmpty())
            return ownScope;

        Map<String, EObject> currentMdObjects = collectCurrentMdObjects(ownScope);
        CompositeScope result = new CompositeScope(ownScope != null ? ownScope : ISlicedScope.NULLSCOPE, true);
        List<String> addedProjects = new ArrayList<>();
        List<String> skippedProjects = new ArrayList<>();

        logProbe("own", project, null, reference, ownScope, resource); //$NON-NLS-1$
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

            IScope currentProjectFriendlyScope = new CurrentProjectFriendlyScope(linkedScope, currentMdObjects);
            logProbe("linked", project, linkedProject, reference, currentProjectFriendlyScope, resource); //$NON-NLS-1$
            result.addScope(currentProjectFriendlyScope);
            addedProjects.add(linkedProjectName);
        }

        logComposition(project, addedProjects, skippedProjects);
        if (!addedProjects.isEmpty())
            logProbe("composite", project, null, reference, result, resource); //$NON-NLS-1$
        return addedProjects.isEmpty() ? ownScope : result;
    }

    private Map<String, EObject> collectCurrentMdObjects(IScope ownScope)
    {
        Map<String, EObject> result = new HashMap<>();
        if (ownScope == null)
            return result;

        for (IEObjectDescription description : ownScope.getAllElements())
        {
            EObject object = description.getEObjectOrProxy();
            if (!(object instanceof DbViewDef))
                continue;

            EObject mdObject = ((DbViewDef)object).getMdObject();
            if (mdObject != null && mdObject.eClass() != null && !mdObject.eIsProxy())
                result.putIfAbsent(mdObject.eClass().getName(), mdObject);
        }
        return result;
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
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String projectName = project != null ? project.getName() : "NULL"; //$NON-NLS-1$
        String key = resource.getClass().getName() + "|" + projectName; //$NON-NLS-1$
        if (loggedResourceCalls.add(key))
        {
            ContextLinks.logDebug("EDT Context Links QL BM provider call resource=" //$NON-NLS-1$
                + resource.getClass().getName() + " project=" + projectName //$NON-NLS-1$
                + " ql=" + isQlResource(resource) + " uri=" + resource.getURI()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private IProject workspaceProject(Resource resource)
    {
        if (resource == null)
            return null;

        IProject project = ContextLinks.getProject(resource.getURI());
        if (project != null)
            return project;

        try
        {
            IResourceLookup resourceLookup = ServiceAccess.get(IResourceLookup.class);
            project = resourceLookup.getProject(resource);
            if (project != null)
                return project;

            IResource platformResource = resourceLookup.getPlatformResource(resource);
            return platformResource != null ? platformResource.getProject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private boolean isUsableLinkedExtension(IProject project, IProject linkedProject)
    {
        return linkedProject != null && linkedProject.isAccessible() && !linkedProject.equals(project)
            && ContextLinks.isExtensionProject(linkedProject);
    }

    private void logComposition(IProject project, List<String> addedProjects, List<String> skippedProjects)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = project.getName() + "|" + addedProjects + "|" + skippedProjects; //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedCompositions.add(key))
        {
            ContextLinks.logDebug("EDT Context Links QL BM scope project=" + project.getName() //$NON-NLS-1$
                + " linked=" + addedProjects + " skipped=" + skippedProjects); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void logProbe(String phase, IProject project, IProject linkedProject, EReference reference, IScope scope,
        Resource resolutionContext)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        ScopeProbeResult result = probeScope(scope, resolutionContext);
        String linkedProjectName = linkedProject != null ? linkedProject.getName() : "-"; //$NON-NLS-1$
        String key = phase + "|" + project.getName() + "|" + linkedProjectName + "|" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + describeReference(reference) + "|" + result.found + "|" + result.scanned + "|" + result.matches; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (loggedProbeResults.add(key))
        {
            ContextLinks.logDebug("EDT Context Links QL probe phase=" + phase //$NON-NLS-1$
                + " project=" + project.getName() + " linkedProject=" + linkedProjectName //$NON-NLS-1$ //$NON-NLS-2$
                + " reference=" + describeReference(reference) + " target=" + QL_PROBE_OBJECT //$NON-NLS-1$ //$NON-NLS-2$
                + " scope=" + describeScope(scope) + " found=" + result.found //$NON-NLS-1$ //$NON-NLS-2$
                + " scanned=" + result.scanned + " truncated=" + result.truncated //$NON-NLS-1$ //$NON-NLS-2$
                + " matches=" + result.matches + " samples=" + result.samples //$NON-NLS-1$
                + " firstElements=" + result.firstElements); //$NON-NLS-1$
        }
    }

    private ScopeProbeResult probeScope(IScope scope, Resource resolutionContext)
    {
        ScopeProbeResult result = new ScopeProbeResult();
        if (scope == null)
            return result;

        String target = QL_PROBE_OBJECT.toLowerCase(Locale.ROOT);
        for (IEObjectDescription description : scope.getAllElements())
        {
            if (result.scanned >= QL_PROBE_SCAN_LIMIT)
            {
                result.truncated = true;
                break;
            }

            result.scanned++;
            String candidate = describeElement(description);
            if (result.firstElements.size() < 8)
                result.firstElements.add(candidate + " " + describeResolution(description, resolutionContext)); //$NON-NLS-1$
            String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
            if (normalizedCandidate.contains(target))
            {
                result.found = true;
                result.matches++;
                if (result.samples.size() < 5)
                    result.samples.add(candidate + " " + describeResolution(description, resolutionContext)); //$NON-NLS-1$
            }
        }
        return result;
    }

    private String describeResolution(IEObjectDescription description, Resource resolutionContext)
    {
        try
        {
            EObject object = description.getEObjectOrProxy();
            EObject resolved = resolutionContext != null && object != null ? EcoreUtil.resolve(object, resolutionContext)
                : object;
            return "proxy=" + describeEObject(object) + " resolved=" + describeEObject(resolved) //$NON-NLS-1$ //$NON-NLS-2$
                + " dbView=" + describeDbView(resolved); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return "resolveError=" + e.getClass().getName() + ":" + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private String describeDbView(EObject object)
    {
        if (!(object instanceof DbViewDef))
            return "-"; //$NON-NLS-1$

        DbViewDef dbView = (DbViewDef)object;
        EObject mdObject = dbView.getMdObject();
        return "name=" + dbView.getName() + ",md=" + describeEObject(mdObject) //$NON-NLS-1$ //$NON-NLS-2$
            + ",folder=" + describeFolderMatch(mdObject); //$NON-NLS-1$
    }

    private String describeFolderMatch(EObject object)
    {
        if (object == null || object.eClass() == null)
            return "NULL"; //$NON-NLS-1$

        return "document=" + (object.eClass() //$NON-NLS-1$
            == MdClassPackage.Literals.CONFIGURATION__DOCUMENTS.getEReferenceType())
            + ",catalog=" + (object.eClass() //$NON-NLS-1$
                == MdClassPackage.Literals.CONFIGURATION__CATALOGS.getEReferenceType())
            + ",class=" + object.eClass().getName(); //$NON-NLS-1$
    }

    private String describeEObject(EObject object)
    {
        if (object == null)
            return "NULL"; //$NON-NLS-1$

        String className = object.eClass() != null ? object.eClass().getName() : object.getClass().getName();
        String uri = object.eResource() != null ? object.eResource().getURIFragment(object) : "NO_RESOURCE"; //$NON-NLS-1$
        return className + "{proxy=" + object.eIsProxy() + ",fragment=" + uri + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String describeElement(IEObjectDescription description)
    {
        if (description == null)
            return "NULL"; //$NON-NLS-1$

        String name = description.getName() != null ? description.getName().toString() : "NULL"; //$NON-NLS-1$
        String uri = description.getEObjectURI() != null ? description.getEObjectURI().toString() : "NULL"; //$NON-NLS-1$
        return name + "@" + uri; //$NON-NLS-1$
    }

    private String describeReference(EReference reference)
    {
        return reference != null ? reference.getName() : "NULL"; //$NON-NLS-1$
    }

    private String describeScope(IScope scope)
    {
        return scope != null ? scope.getClass().getName() + "@" //$NON-NLS-1$
            + Integer.toHexString(System.identityHashCode(scope)) : "NULL"; //$NON-NLS-1$
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

    private static final class ScopeProbeResult
    {
        private boolean found;
        private int matches;
        private int scanned;
        private boolean truncated;
        private final List<String> samples = new ArrayList<>();
        private final List<String> firstElements = new ArrayList<>();
    }

    private static final class CurrentProjectFriendlyScope
        implements IScope
    {
        private final IScope delegate;
        private final Map<String, EObject> currentMdObjects;

        CurrentProjectFriendlyScope(IScope delegate, Map<String, EObject> currentMdObjects)
        {
            this.delegate = delegate;
            this.currentMdObjects = currentMdObjects;
        }

        @Override
        public IEObjectDescription getSingleElement(QualifiedName name)
        {
            return wrap(delegate.getSingleElement(name));
        }

        @Override
        public Iterable<IEObjectDescription> getElements(QualifiedName name)
        {
            return wrap(delegate.getElements(name));
        }

        @Override
        public IEObjectDescription getSingleElement(EObject object)
        {
            return wrap(delegate.getSingleElement(object));
        }

        @Override
        public Iterable<IEObjectDescription> getElements(EObject object)
        {
            return wrap(delegate.getElements(object));
        }

        @Override
        public Iterable<IEObjectDescription> getAllElements()
        {
            return wrap(delegate.getAllElements());
        }

        private Iterable<IEObjectDescription> wrap(Iterable<IEObjectDescription> descriptions)
        {
            List<IEObjectDescription> result = new ArrayList<>();
            for (IEObjectDescription description : descriptions)
                result.add(wrap(description));
            return result;
        }

        private IEObjectDescription wrap(IEObjectDescription description)
        {
            if (description == null)
                return null;

            EObject object = description.getEObjectOrProxy();
            if (!(object instanceof DbViewDef))
                return description;

            DbViewDef dbView = (DbViewDef)object;
            EObject mdObject = dbView.getMdObject();
            if (mdObject == null || mdObject.eClass() == null)
                return description;

            EObject currentMdObject = currentMdObjects.get(mdObject.eClass().getName());
            if (currentMdObject == null)
                return description;

            return new CurrentProjectFriendlyDescription(description, dbView, currentMdObject);
        }
    }

    private static final class CurrentProjectFriendlyDescription
        implements IEObjectDescription
    {
        private final IEObjectDescription delegate;
        private final EObject friendlyObject;

        CurrentProjectFriendlyDescription(IEObjectDescription delegate, DbViewDef dbView, EObject currentMdObject)
        {
            this.delegate = delegate;
            this.friendlyObject = (EObject)Proxy.newProxyInstance(dbView.getClass().getClassLoader(),
                new Class<?>[] { DbViewDef.class, DbViewTableDef.class, Table.class, InternalEObject.class },
                new FriendlyDbViewInvocationHandler(dbView, currentMdObject));
        }

        @Override
        public QualifiedName getName()
        {
            return delegate.getName();
        }

        @Override
        public QualifiedName getQualifiedName()
        {
            return delegate.getQualifiedName();
        }

        @Override
        public EObject getEObjectOrProxy()
        {
            return friendlyObject;
        }

        @Override
        public URI getEObjectURI()
        {
            return delegate.getEObjectURI();
        }

        @Override
        public org.eclipse.emf.ecore.EClass getEClass()
        {
            return delegate.getEClass();
        }

        @Override
        public String getUserData(String name)
        {
            return delegate.getUserData(name);
        }

        @Override
        public String[] getUserDataKeys()
        {
            return delegate.getUserDataKeys();
        }
    }

    private static final class FriendlyDbViewInvocationHandler
        implements InvocationHandler
    {
        private final DbViewDef delegate;
        private final EObject currentMdObject;

        FriendlyDbViewInvocationHandler(DbViewDef delegate, EObject currentMdObject)
        {
            this.delegate = delegate;
            this.currentMdObject = currentMdObject;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
                return method.invoke(this, args);

            if ("getMdObject".equals(method.getName()) && method.getParameterCount() == 0) //$NON-NLS-1$
                return isQueryWizardProjectMembershipCheck() ? currentMdObject : delegate.getMdObject();
            if ("getFields".equals(method.getName()) && method.getParameterCount() == 0) //$NON-NLS-1$
                return wrapDbViewElements((EList<?>)method.invoke(delegate, args), currentMdObject);
            Object rootContextValue = currentProjectRootContextValue(method, currentMdObject);
            if (rootContextValue != NO_CONTEXT_VALUE)
                return rootContextValue;

            try
            {
                return method.invoke(delegate, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        }
    }

    private static final Object NO_CONTEXT_VALUE = new Object();

    private static Object currentProjectRootContextValue(Method method, EObject currentMdObject)
    {
        if (method.getParameterCount() != 0 || currentMdObject == null)
            return NO_CONTEXT_VALUE;

        switch (method.getName())
        {
        case "eContainer": //$NON-NLS-1$
            return currentMdObject.eContainer();
        case "eContainingFeature": //$NON-NLS-1$
            return currentMdObject.eContainingFeature();
        case "eContainmentFeature": //$NON-NLS-1$
            return currentMdObject.eContainmentFeature();
        case "eResource": //$NON-NLS-1$
            return currentMdObject.eResource();
        default:
            return currentProjectInternalRootContextValue(method, currentMdObject);
        }
    }

    private static Object currentProjectInternalRootContextValue(Method method, EObject currentMdObject)
    {
        if (!(currentMdObject instanceof InternalEObject internalCurrentMdObject))
            return NO_CONTEXT_VALUE;

        switch (method.getName())
        {
        case "eInternalContainer": //$NON-NLS-1$
            return internalCurrentMdObject.eInternalContainer();
        case "eInternalResource": //$NON-NLS-1$
            return internalCurrentMdObject.eInternalResource();
        case "eDirectResource": //$NON-NLS-1$
            return internalCurrentMdObject.eDirectResource();
        default:
            return NO_CONTEXT_VALUE;
        }
    }

    private static Object wrapDbViewElement(DbViewElement element, EObject currentMdObject)
    {
        if (element == null)
            return null;

        Set<Class<?>> interfaces = new HashSet<>();
        collectPublicInterfaces(element.getClass(), interfaces);
        interfaces.add(DbViewElement.class);
        interfaces.add(EObject.class);
        interfaces.add(InternalEObject.class);

        return Proxy.newProxyInstance(element.getClass().getClassLoader(),
            interfaces.toArray(new Class<?>[interfaces.size()]),
            new FriendlyDbViewElementInvocationHandler(element, currentMdObject));
    }

    private static EList<Object> wrapDbViewElements(EList<?> elements, EObject currentMdObject)
    {
        BasicEList<Object> result = new BasicEList<>();
        if (elements == null)
            return result;

        for (Object element : elements)
        {
            if (element instanceof DbViewElement)
                result.add(wrapDbViewElement((DbViewElement)element, currentMdObject));
            else
                result.add(element);
        }
        return result;
    }

    private static void collectPublicInterfaces(Class<?> type, Set<Class<?>> interfaces)
    {
        if (type == null)
            return;

        for (Class<?> interfaceType : type.getInterfaces())
        {
            if (Modifier.isPublic(interfaceType.getModifiers()))
                interfaces.add(interfaceType);
            collectPublicInterfaces(interfaceType, interfaces);
        }
        collectPublicInterfaces(type.getSuperclass(), interfaces);
    }

    private static final class FriendlyDbViewElementInvocationHandler
        implements InvocationHandler
    {
        private final DbViewElement delegate;
        private final EObject currentMdObject;

        FriendlyDbViewElementInvocationHandler(DbViewElement delegate, EObject currentMdObject)
        {
            this.delegate = delegate;
            this.currentMdObject = currentMdObject;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
                return method.invoke(this, args);

            if ("getMdObject".equals(method.getName()) && method.getParameterCount() == 0) //$NON-NLS-1$
                return isQueryWizardProjectMembershipCheck() ? currentMdObject : delegate.getMdObject();
            if ("getFields".equals(method.getName()) && method.getParameterCount() == 0) //$NON-NLS-1$
                return wrapDbViewElements((EList<?>)method.invoke(delegate, args), currentMdObject);

            try
            {
                return method.invoke(delegate, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        }
    }

    private static boolean isQueryWizardProjectMembershipCheck()
    {
        for (StackTraceElement element : Thread.currentThread().getStackTrace())
        {
            if ("isObjectBelongsToCurrentProject".equals(element.getMethodName()) //$NON-NLS-1$
                && element.getClassName().endsWith("QueryWizardServiceUtils")) //$NON-NLS-1$
                return true;
        }
        return false;
    }
}
