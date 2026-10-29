package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.Table;

/** Presents linked extension fields on the existing base tables of an external processor/report. */
final class ContextLinksExternalQueryScope implements IScope
{
    private final IScope ownScope;
    private final List<IScope> linkedScopes;
    private final Resource resolutionContext;
    private final Map<QualifiedName, IEObjectDescription> descriptions = new HashMap<>();
    private List<IEObjectDescription> allElements;

    ContextLinksExternalQueryScope(IScope ownScope, List<IScope> linkedScopes)
    {
        this(ownScope, linkedScopes, null);
    }

    ContextLinksExternalQueryScope(IScope ownScope, List<IScope> linkedScopes, Resource resolutionContext)
    {
        this.ownScope = ownScope != null ? ownScope : IScope.NULLSCOPE;
        this.linkedScopes = List.copyOf(linkedScopes);
        this.resolutionContext = resolutionContext;
    }

    @Override
    public synchronized IEObjectDescription getSingleElement(QualifiedName name)
    {
        IEObjectDescription cached = descriptions.get(name);
        if (cached != null)
            return cached;
        List<IEObjectDescription> variants = new ArrayList<>();
        ownScope.getElements(name).forEach(variants::add);
        for (IScope scope : linkedScopes)
            scope.getElements(name).forEach(variants::add);
        if (variants.isEmpty())
            return null;
        IEObjectDescription result = merge(variants);
        descriptions.put(name, result);
        return result;
    }

    @Override
    public Iterable<IEObjectDescription> getElements(QualifiedName name)
    {
        IEObjectDescription result = getSingleElement(name);
        return result != null ? List.of(result) : List.of();
    }

    @Override
    public IEObjectDescription getSingleElement(EObject object)
    {
        var iterator = getElements(object).iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public Iterable<IEObjectDescription> getElements(EObject object)
    {
        Map<QualifiedName, IEObjectDescription> result = new LinkedHashMap<>();
        List<IScope> scopes = new ArrayList<>();
        scopes.add(ownScope);
        scopes.addAll(linkedScopes);
        for (IScope scope : scopes)
            for (IEObjectDescription description : scope.getElements(object))
            {
                QualifiedName name = description.getName();
                IEObjectDescription merged = getSingleElement(name);
                if (merged != null)
                    result.putIfAbsent(name, merged);
            }
        return List.copyOf(result.values());
    }

    @Override
    public synchronized Iterable<IEObjectDescription> getAllElements()
    {
        if (allElements != null)
            return allElements;
        Map<QualifiedName, List<IEObjectDescription>> grouped = new LinkedHashMap<>();
        add(grouped, ownScope);
        for (IScope scope : linkedScopes)
            add(grouped, scope);
        List<IEObjectDescription> result = new ArrayList<>(grouped.size());
        for (var entry : grouped.entrySet())
        {
            IEObjectDescription description = descriptions.computeIfAbsent(entry.getKey(), key -> merge(entry.getValue()));
            result.add(description);
        }
        allElements = List.copyOf(result);
        return allElements;
    }

    private void add(Map<QualifiedName, List<IEObjectDescription>> grouped, IScope scope)
    {
        for (IEObjectDescription description : scope.getAllElements())
            grouped.computeIfAbsent(description.getName(), key -> new ArrayList<>()).add(description);
    }

    private IEObjectDescription merge(List<IEObjectDescription> variants)
    {
        IEObjectDescription anchor = variants.get(0);
        if (variants.size() == 1)
            return anchor;
        List<EObject> tables = new ArrayList<>();
        for (IEObjectDescription variant : variants)
        {
            EObject object = variant.getEObjectOrProxy();
            if (object.eIsProxy() && resolutionContext != null)
                object = EcoreUtil.resolve(object, resolutionContext);
            if (!(object instanceof Table) || object.eIsProxy())
                return anchor;
            if (!tables.contains(object))
                tables.add(object);
        }
        if (tables.size() < 2)
            return anchor;
        EObject projection = project(tables);
        return new Description(anchor, projection);
    }

    private static EObject project(List<EObject> variants)
    {
        EObject anchor = variants.get(0);
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfaces(anchor.getClass(), interfaces);
        return (EObject)Proxy.newProxyInstance(anchor.getClass().getClassLoader(), interfaces.toArray(Class<?>[]::new),
            new TableProjection(anchor, variants));
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> result)
    {
        if (type == null)
            return;
        for (Class<?> iface : type.getInterfaces())
        {
            if (Modifier.isPublic(iface.getModifiers()))
                result.add(iface);
            collectInterfaces(iface, result);
        }
        collectInterfaces(type.getSuperclass(), result);
    }

    private static final class TableProjection implements InvocationHandler
    {
        private final EObject anchor;
        private final List<EObject> variants;
        private EList<DbViewFieldDef> fields;

        TableProjection(EObject anchor, List<EObject> variants)
        {
            this.anchor = anchor;
            this.variants = List.copyOf(variants);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            if (method.getDeclaringClass() == Object.class && "equals".equals(method.getName())) //$NON-NLS-1$
                return proxy == args[0] || anchor.equals(unwrap(args[0]));
            if ("getFields".equals(method.getName()) && method.getParameterCount() == 0) //$NON-NLS-1$
                return fields();
            if (args != null && args.length > 0 && isFieldsFeature(args[0]))
            {
                if ("eGet".equals(method.getName())) //$NON-NLS-1$
                    return fields();
                if ("eIsSet".equals(method.getName())) //$NON-NLS-1$
                    return !fields().isEmpty();
            }
            try
            {
                // Ownership, EClass, visibility, names, URI and scalar field types remain those of the base view.
                return method.invoke(anchor, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        }

        private boolean isFieldsFeature(Object value)
        {
            EStructuralFeature feature = value instanceof EStructuralFeature ? (EStructuralFeature)value
                : value instanceof Integer ? anchor.eClass().getEStructuralFeature(((Integer)value).intValue()) : null;
            return feature != null && "fields".equals(feature.getName()); //$NON-NLS-1$
        }

        private synchronized EList<DbViewFieldDef> fields()
        {
            if (fields != null)
                return fields;
            Map<String, List<DbViewFieldDef>> grouped = new LinkedHashMap<>();
            for (EObject variant : variants)
                for (DbViewFieldDef field : ((Table)variant).getFields())
                {
                    String name = field.getName();
                    String key = name != null ? name.toLowerCase(Locale.ROOT) : String.valueOf(EcoreUtil.getURI(field));
                    List<DbViewFieldDef> values = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (!values.contains(field))
                        values.add(field);
                }
            BasicEList<DbViewFieldDef> merged = new BasicEList<>();
            for (List<DbViewFieldDef> values : grouped.values())
            {
                DbViewFieldDef field = values.get(0);
                if (values.size() > 1 && values.stream().allMatch(value -> value instanceof Table))
                    field = (DbViewFieldDef)project(new ArrayList<>(values));
                merged.add(field);
            }
            fields = ECollections.unmodifiableEList(merged);
            return fields;
        }

        private static Object unwrap(Object value)
        {
            if (value != null && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof TableProjection projection)
                return projection.anchor;
            return value;
        }
    }

    private static final class Description implements IEObjectDescription
    {
        private final IEObjectDescription anchor;
        private final EObject projection;

        Description(IEObjectDescription anchor, EObject projection)
        {
            this.anchor = anchor;
            this.projection = projection;
        }

        @Override public QualifiedName getName() { return anchor.getName(); }
        @Override public QualifiedName getQualifiedName() { return anchor.getQualifiedName(); }
        @Override public EObject getEObjectOrProxy() { return projection; }
        @Override public URI getEObjectURI() { return anchor.getEObjectURI(); }
        @Override public org.eclipse.emf.ecore.EClass getEClass() { return anchor.getEClass(); }
        @Override public String getUserData(String name) { return anchor.getUserData(name); }
        @Override public String[] getUserDataKeys() { return anchor.getUserDataKeys(); }
    }
}
