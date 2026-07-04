package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;

import com._1c.g5.v8.dt.bsl.model.BslDerivedPropertySource;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.NameExpression;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainer;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Adds query-result columns from service comments to EDT-computed value table types.
 */
@Singleton
public class ContextLinksTypesComputer
    extends TypesComputer
{
    private static final Set<String> VALUE_TABLE_NAMES = Set.of(
        "valuetable", //$NON-NLS-1$
        "\u0442\u0430\u0431\u043b\u0438\u0446\u0430\u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0439"); //$NON-NLS-1$
    private static final Set<String> VALUE_TABLE_ROW_NAMES = Set.of(
        "valuetablerow", //$NON-NLS-1$
        "\u0441\u0442\u0440\u043e\u043a\u0430\u0442\u0430\u0431\u043b\u0438\u0446\u044b\u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0439"); //$NON-NLS-1$
    private static final Set<String> QUERY_RESULT_SELECTION_NAMES = Set.of(
        "queryresultselection", //$NON-NLS-1$
        "\u0432\u044b\u0431\u043e\u0440\u043a\u0430\u0438\u0437\u0440\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442\u0430\u0437\u0430\u043f\u0440\u043e\u0441\u0430"); //$NON-NLS-1$
    private static final Set<String> VALUE_TABLE_ROW_RETURN_METHODS = Set.of(
        "add", //$NON-NLS-1$
        "\u0434\u043e\u0431\u0430\u0432\u0438\u0442\u044c", //$NON-NLS-1$
        "insert", //$NON-NLS-1$
        "\u0432\u0441\u0442\u0430\u0432\u0438\u0442\u044c", //$NON-NLS-1$
        "find", //$NON-NLS-1$
        "\u043d\u0430\u0439\u0442\u0438", //$NON-NLS-1$
        "chooserow", //$NON-NLS-1$
        "\u0432\u044b\u0431\u0440\u0430\u0442\u044c\u0441\u0442\u0440\u043e\u043a\u0443", //$NON-NLS-1$
        "get", //$NON-NLS-1$
        "\u043f\u043e\u043b\u0443\u0447\u0438\u0442\u044c"); //$NON-NLS-1$
    private static final Set<String> VALUE_TABLE_RETURN_METHODS = Set.of(
        "copy", //$NON-NLS-1$
        "\u0441\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u0442\u044c"); //$NON-NLS-1$
    private static final Set<String> UNLOAD_METHOD_NAMES = Set.of(
        "unload", //$NON-NLS-1$
        "\u0432\u044b\u0433\u0440\u0443\u0437\u0438\u0442\u044c"); //$NON-NLS-1$
    private static final Set<String> COLUMN_COLLECTION_PROPERTY_NAMES = Set.of(
        "columns", //$NON-NLS-1$
        "\u043a\u043e\u043b\u043e\u043d\u043a\u0438"); //$NON-NLS-1$
    private static final Set<String> loggedEnrichKeys = ConcurrentHashMap.newKeySet();

    @Inject(optional = true)
    private IRuntimeVersionSupport versionSupport;

    @Inject(optional = true)
    private Provider<IScopeProvider> scopeProviderProvider;

    @Inject(optional = true)
    private Provider<IQualifiedNameConverter> qualifiedNameConverterProvider;

    @Override
    public List<TypeItem> computeTypes(EObject object, Environments environments)
    {
        List<TypeItem> types = super.computeTypes(object, environments);
        if (object == null || types == null || types.isEmpty())
            return types;

        try
        {
            return enrichTypes(object, environments, types);
        }
        catch (RuntimeException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [query-result.types.error] " //$NON-NLS-1$
                + e.getClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$
            return types;
        }
    }

    private List<TypeItem> enrichTypes(EObject object, Environments environments, List<TypeItem> types)
    {
        Resource resource = object.eResource();
        URI resourceUri = resource != null ? resource.getURI() : null;
        if (ContextLinks.shouldSkipBslContextExtension("bsl-query-result-comment-types", //$NON-NLS-1$
            ContextLinks.getProject(resourceUri)))
            return types;

        QueryResultServiceCommentIndex index = QueryResultServiceCommentIndex.get(object);
        if (index.isEmpty())
            return types;

        QueryResultServiceCommentIndex.ResultTable table = findTable(object, index);
        if (table == null || table.getColumns().isEmpty())
            return types;

        if (isColumnCollectionAccess(object))
            return enrichColumnCollectionTypes(object, environments, types, table);

        List<TypeItem> result = new ArrayList<>(types.size());
        boolean changed = false;
        for (TypeItem typeItem : types)
        {
            TypeItem enriched = enrichTypeItem(typeItem, table, object, environments);
            result.add(enriched);
            changed |= enriched != typeItem;
        }

        if (changed)
            logEnriched(object, resourceUri, table);
        return changed ? result : types;
    }

    private QueryResultServiceCommentIndex.ResultTable findTable(EObject object,
        QueryResultServiceCommentIndex index)
    {
        for (String key : candidateKeys(object))
        {
            QueryResultServiceCommentIndex.ResultTable table = index.getTable(key);
            if (table != null)
                return table;
        }
        return null;
    }

    private Set<String> candidateKeys(EObject object)
    {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addKey(result, keyOf(object));
        addKey(result, assignmentLeftKey(object));
        addKey(result, invocationSourceKey(object));
        addKey(result, columnCollectionSourceKey(object));
        addKey(result, actualText(object));
        return result;
    }

    private String assignmentLeftKey(EObject object)
    {
        EObject current = object;
        while (current != null)
        {
            if (current instanceof SimpleStatement statement && isSelfOrAncestor(statement.getRight(), object))
                return keyOf(statement.getLeft());
            current = current.eContainer();
        }
        return null;
    }

    private String invocationSourceKey(EObject object)
    {
        if (!(object instanceof Invocation invocation))
            return null;

        FeatureAccess methodAccess = invocation.getMethodAccess();
        if (!(methodAccess instanceof DynamicFeatureAccess dynamicMethodAccess)
            || !containsName(UNLOAD_METHOD_NAMES, methodAccess.getName()))
            return null;

        return keyOf(dynamicMethodAccess.getSource());
    }

    private String columnCollectionSourceKey(EObject object)
    {
        if (object instanceof DynamicFeatureAccess dynamicFeatureAccess
            && containsName(COLUMN_COLLECTION_PROPERTY_NAMES, dynamicFeatureAccess.getName()))
            return keyOf(dynamicFeatureAccess.getSource());
        return null;
    }

    private boolean isColumnCollectionAccess(EObject object)
    {
        return object instanceof DynamicFeatureAccess dynamicFeatureAccess
            && containsName(COLUMN_COLLECTION_PROPERTY_NAMES, dynamicFeatureAccess.getName());
    }

    private String keyOf(EObject object)
    {
        if (object instanceof NameExpression nameExpression)
            return nameExpression.getName();
        if (object instanceof Variable variable)
            return variable.getName();
        if (object instanceof IndexAccess indexAccess)
            return actualText(indexAccess);
        if (object instanceof FeatureAccess featureAccess)
            return featureAccess.getName();
        return null;
    }

    private static void addKey(Set<String> keys, String key)
    {
        if (key != null && !key.isBlank())
            keys.add(key);
    }

    private static boolean isSelfOrAncestor(EObject ancestor, EObject object)
    {
        EObject current = object;
        while (current != null)
        {
            if (current == ancestor)
                return true;
            current = current.eContainer();
        }
        return false;
    }

    private static String actualText(EObject object)
    {
        if (object == null)
            return null;
        ICompositeNode node = NodeModelUtils.getNode(object);
        return node != null ? node.getText() : null;
    }

    private TypeItem enrichTypeItem(TypeItem typeItem, QueryResultServiceCommentIndex.ResultTable table,
        EObject anchor, Environments environments)
    {
        Type type = asResolvedType(typeItem, anchor);
        if (type == null)
            return typeItem;

        if (isValueTable(type))
        {
            Type enrichedType = enrichValueTable(type, table, anchor, environments);
            return enrichedType != null ? enrichedType : typeItem;
        }
        if (isValueTableRow(type))
            return enrichValueTableRow(type, table, anchor, environments);
        if (isQueryResultSelection(type))
            return enrichQueryResultSelection(type, table, anchor, environments);
        return typeItem;
    }

    private Type enrichValueTable(Type sourceType, QueryResultServiceCommentIndex.ResultTable table,
        EObject anchor, Environments environments)
    {
        Type tableType = cloneType(sourceType);
        Type rowType = cloneCollectionRowType(sourceType, anchor);
        if (rowType == null)
            rowType = resolveStandardRowType(anchor);
        if (rowType == null)
            return null;

        enrichRowContext(rowType, table, anchor, environments);

        TypeContainerRef collectionElementTypes = McoreFactory.eINSTANCE.createTypeContainerRef();
        collectionElementTypes.getTypes().add(rowType);
        tableType.setCollectionElementTypes(collectionElementTypes);
        patchValueTableMethods(tableType, rowType);
        return tableType;
    }

    private Type enrichValueTableRow(Type sourceType, QueryResultServiceCommentIndex.ResultTable table,
        EObject anchor, Environments environments)
    {
        Type rowType = cloneType(sourceType);
        enrichRowContext(rowType, table, anchor, environments);
        return rowType;
    }

    private Type enrichQueryResultSelection(Type sourceType, QueryResultServiceCommentIndex.ResultTable table,
        EObject anchor, Environments environments)
    {
        Type selectionType = cloneType(sourceType);
        enrichRowContext(selectionType, table, anchor, environments);
        return selectionType;
    }

    private List<TypeItem> enrichColumnCollectionTypes(EObject object, Environments environments,
        List<TypeItem> types, QueryResultServiceCommentIndex.ResultTable table)
    {
        List<TypeItem> result = new ArrayList<>(types.size());
        boolean changed = false;
        for (TypeItem typeItem : types)
        {
            Type type = asResolvedType(typeItem, object);
            if (type == null)
            {
                result.add(typeItem);
                continue;
            }

            Type collectionType = cloneType(type);
            enrichColumnCollectionContext(collectionType, table, object, environments);
            result.add(collectionType);
            changed = true;
        }
        return changed ? result : types;
    }

    private Type cloneCollectionRowType(Type tableType, EObject anchor)
    {
        TypeContainer collectionElementTypes = tableType.getCollectionElementTypes();
        if (collectionElementTypes == null)
            return null;

        for (TypeItem typeItem : collectionElementTypes.allTypes())
        {
            Type type = asResolvedType(typeItem, anchor);
            if (type != null && isValueTableRow(type))
                return cloneType(type);
        }
        return null;
    }

    private Type resolveStandardRowType(EObject anchor)
    {
        Type rowType = firstResolvedType("ValueTableRow", anchor); //$NON-NLS-1$
        if (rowType != null)
            return cloneType(rowType);

        rowType = firstResolvedType("\u0421\u0442\u0440\u043e\u043a\u0430\u0422\u0430\u0431\u043b\u0438\u0446\u044b\u0417\u043d\u0430\u0447\u0435\u043d\u0438\u0439", anchor); //$NON-NLS-1$
        return rowType != null ? cloneType(rowType) : null;
    }

    private Type firstResolvedType(String name, EObject anchor)
    {
        IEObjectProvider provider = typeProvider(anchor);
        if (provider == null)
            return null;

        List<TypeItem> typeItems = resolveTypeItems(name, anchor, provider);
        for (TypeItem typeItem : typeItems)
        {
            Type type = asResolvedType(typeItem, anchor);
            if (type != null)
                return type;
        }
        return null;
    }

    private void enrichRowContext(Type rowType, QueryResultServiceCommentIndex.ResultTable table,
        EObject anchor, Environments environments)
    {
        ContextDef contextDef = ensureContextDef(rowType, environments);
        Set<String> existingPropertyNames = existingPropertyNames(contextDef);
        for (QueryResultServiceCommentIndex.Column column : table.getColumns())
        {
            String name = column.getName();
            if (name.isBlank() || existingPropertyNames.contains(normalizeName(name)))
                continue;

            DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
            property.setName(name);
            property.setNameRu(name);
            property.setReadable(true);
            property.setWritable(true);
            property.setEnvironments(environments != null ? environments : Environments.ALL);
            property.setTypeContainer(createTypeContainer(column, anchor));
            property.setSource(createPropertySource(anchor, column));
            contextDef.getProperties().add(property);
            existingPropertyNames.add(normalizeName(name));
        }
    }

    private void enrichColumnCollectionContext(Type collectionType,
        QueryResultServiceCommentIndex.ResultTable table, EObject anchor, Environments environments)
    {
        ContextDef contextDef = ensureContextDef(collectionType, environments);
        Set<String> existingPropertyNames = existingPropertyNames(contextDef);
        TypeContainerRef columnTypeContainer = createValueTableColumnTypeContainer(anchor);
        for (QueryResultServiceCommentIndex.Column column : table.getColumns())
        {
            String name = column.getName();
            if (name.isBlank() || existingPropertyNames.contains(normalizeName(name)))
                continue;

            DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
            property.setName(name);
            property.setNameRu(name);
            property.setReadable(true);
            property.setWritable(false);
            property.setEnvironments(environments != null ? environments : Environments.ALL);
            property.setTypeContainer(EcoreUtil2.cloneWithProxies(columnTypeContainer));
            property.setSource(createPropertySource(anchor, column));
            contextDef.getProperties().add(property);
            existingPropertyNames.add(normalizeName(name));
        }
    }

    private ContextDef ensureContextDef(Type type, Environments environments)
    {
        ContextDef contextDef = type.getContextDef();
        if (contextDef == null)
        {
            contextDef = McoreFactory.eINSTANCE.createContextDef();
            type.setContextDef(contextDef);
        }

        if (contextDef.getEnvironments() == null)
            contextDef.setEnvironments(environments != null ? environments : Environments.ALL);
        return contextDef;
    }

    private Set<String> existingPropertyNames(ContextDef contextDef)
    {
        Set<String> result = new LinkedHashSet<>();
        for (Property property : contextDef.allProperties())
        {
            addNormalizedName(result, property.getName());
            addNormalizedName(result, property.getNameRu());
        }
        return result;
    }

    private TypeContainerRef createTypeContainer(QueryResultServiceCommentIndex.Column column, EObject anchor)
    {
        TypeContainerRef typeContainer = McoreFactory.eINSTANCE.createTypeContainerRef();
        IEObjectProvider provider = typeProvider(anchor);
        if (provider == null)
            return typeContainer;

        Set<String> addedTypeNames = new LinkedHashSet<>();
        for (String typeName : column.getTypeNames())
        {
            for (TypeItem typeItem : resolveTypeItems(typeName, anchor, provider))
            {
                if (typeItem == null)
                    continue;

                String normalizedTypeName = normalizeName(typeName(typeItem));
                if (addedTypeNames.add(normalizedTypeName))
                    typeContainer.getTypes().add(typeItem);
            }
        }
        return typeContainer;
    }

    private TypeContainerRef createValueTableColumnTypeContainer(EObject anchor)
    {
        TypeContainerRef typeContainer = McoreFactory.eINSTANCE.createTypeContainerRef();
        IEObjectProvider provider = typeProvider(anchor);
        if (provider == null)
            return typeContainer;

        addFirstResolvedType(typeContainer, "ValueTableColumn", anchor, provider); //$NON-NLS-1$
        if (typeContainer.getTypes().isEmpty())
        {
            addFirstResolvedType(typeContainer,
                "\u041a\u043e\u043b\u043e\u043d\u043a\u0430\u0422\u0430\u0431\u043b\u0438\u0446\u044b\u0417\u043d\u0430\u0447\u0435\u043d\u0438\u0439", anchor, provider); //$NON-NLS-1$
        }
        return typeContainer;
    }

    private void addFirstResolvedType(TypeContainerRef typeContainer, String typeName, EObject anchor,
        IEObjectProvider provider)
    {
        for (TypeItem typeItem : resolveTypeItems(typeName, anchor, provider))
        {
            if (typeItem != null)
            {
                typeContainer.getTypes().add(typeItem);
                return;
            }
        }
    }

    private List<TypeItem> resolveTypeItems(String typeName, EObject anchor, IEObjectProvider provider)
    {
        List<TypeItem> result = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        for (TypeItem typeItem : getTypeByNameFromScope(typeName, anchor))
            addResolvedTypeItem(result, added, typeItem);
        for (TypeItem typeItem : getTypeByName(typeName, provider))
            addResolvedTypeItem(result, added, typeItem);
        return result;
    }

    private void addResolvedTypeItem(List<TypeItem> result, Set<String> added, TypeItem typeItem)
    {
        if (typeItem == null)
            return;

        String key = typeItem.eIsProxy() ? String.valueOf(EcoreUtil.getURI(typeItem))
            : normalizeName(typeName(typeItem));
        if (added.add(key))
            result.add(typeItem);
    }

    private List<TypeItem> getTypeByNameFromScope(String typeName, EObject anchor)
    {
        if (scopeProviderProvider == null || qualifiedNameConverterProvider == null || anchor == null)
            return List.of();

        try
        {
            IScopeProvider scopeProvider = scopeProviderProvider.get();
            IQualifiedNameConverter qualifiedNameConverter = qualifiedNameConverterProvider.get();
            if (scopeProvider == null || qualifiedNameConverter == null)
                return List.of();

            IScope scope = scopeProvider.getScope(anchor, BslPackage.Literals.EXPRESSION__TYPES);
            if (scope == null)
                return List.of();

            return new ArrayList<>(BslUtil.trasformListTypeNamesToTypeItems(List.of(typeName), scope,
                qualifiedNameConverter));
        }
        catch (RuntimeException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [query-result.types.scope.skip] type=" //$NON-NLS-1$
                + typeName + " error=" + e.getClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return List.of();
        }
    }

    private List<TypeItem> getTypeByName(String typeName, IEObjectProvider provider)
    {
        if (provider == null)
            return List.of();

        try
        {
            List<TypeItem> typeItems = BslUtil.getTypeByName(typeName, provider);
            return typeItems != null ? typeItems : List.of();
        }
        catch (RuntimeException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [query-result.types.resolve.skip] type=" //$NON-NLS-1$
                + typeName + " error=" + e.getClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return List.of();
        }
    }

    private IEObjectProvider typeProvider(EObject anchor)
    {
        Version version = Version.LATEST;
        if (versionSupport != null)
            version = versionSupport.getRuntimeVersionOrDefault(anchor, Version.LATEST);
        return IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
    }

    private BslDerivedPropertySource createPropertySource(EObject anchor,
        QueryResultServiceCommentIndex.Column column)
    {
        BslDerivedPropertySource source = BslFactory.eINSTANCE.createBslDerivedPropertySource();
        if (anchor != null && anchor.eResource() != null)
            source.setModuleUri(anchor.eResource().getURI().toString());

        com._1c.g5.v8.dt.bsl.model.Method method =
            EcoreUtil2.getContainerOfType(anchor, com._1c.g5.v8.dt.bsl.model.Method.class);
        if (method != null)
            source.setMethodName(method.getName());
        source.setLocalOffset(column.getOffset());
        return source;
    }

    private void patchValueTableMethods(Type tableType, Type rowType)
    {
        ContextDef contextDef = tableType.getContextDef();
        if (contextDef == null)
            return;

        for (com._1c.g5.v8.dt.mcore.Method method : contextDef.getMethods())
        {
            if (containsName(VALUE_TABLE_ROW_RETURN_METHODS, method.getName())
                || containsName(VALUE_TABLE_ROW_RETURN_METHODS, method.getNameRu()))
                replaceReturnType(method, rowType);
            else if (containsName(VALUE_TABLE_RETURN_METHODS, method.getName())
                || containsName(VALUE_TABLE_RETURN_METHODS, method.getNameRu()))
                replaceReturnType(method, tableType);
        }
    }

    private static void replaceReturnType(com._1c.g5.v8.dt.mcore.Method method, TypeItem type)
    {
        method.setRetVal(true);
        method.getRetValType().clear();
        method.getRetValType().add(type);
    }

    private static Type asResolvedType(TypeItem typeItem, EObject anchor)
    {
        if (!(typeItem instanceof Type))
            return null;

        EObject resolved = EcoreUtil.resolve(typeItem, anchor);
        if (!(resolved instanceof Type type) || type.eIsProxy())
            return null;
        return type;
    }

    private static Type cloneType(Type type)
    {
        return EcoreUtil2.cloneWithProxies(type);
    }

    private static boolean isValueTable(TypeItem typeItem)
    {
        return hasTypeName(typeItem, VALUE_TABLE_NAMES);
    }

    private static boolean isValueTableRow(TypeItem typeItem)
    {
        return hasTypeName(typeItem, VALUE_TABLE_ROW_NAMES);
    }

    private static boolean isQueryResultSelection(TypeItem typeItem)
    {
        return hasTypeName(typeItem, QUERY_RESULT_SELECTION_NAMES);
    }

    private static boolean hasTypeName(TypeItem typeItem, Set<String> expectedNames)
    {
        return containsName(expectedNames, McoreUtil.getTypeName(typeItem))
            || containsName(expectedNames, McoreUtil.getTypeNameRu(typeItem));
    }

    private static boolean containsName(Set<String> names, String value)
    {
        return value != null && names.contains(normalizeName(value));
    }

    private static void addNormalizedName(Set<String> names, String value)
    {
        if (value != null && !value.isBlank())
            names.add(normalizeName(value));
    }

    private static String typeName(TypeItem typeItem)
    {
        String name = McoreUtil.getTypeName(typeItem);
        if (name == null || name.isBlank())
            name = McoreUtil.getTypeNameRu(typeItem);
        return name != null ? name : ""; //$NON-NLS-1$
    }

    private static String normalizeName(String value)
    {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void logEnriched(EObject object, URI resourceUri,
        QueryResultServiceCommentIndex.ResultTable table)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        String key = resourceUri + "|" + table.getKey() + "|" + object.eClass().getName(); //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedEnrichKeys.add(key))
        {
            ContextLinks.logDebug("EDT Extension Tweaks DEBUG [query-result.types.enrich] moduleUri=" //$NON-NLS-1$
                + resourceUri + " object=" + object.eClass().getName() //$NON-NLS-1$
                + " table=" + table.getKey() //$NON-NLS-1$
                + " columns=" + table.getColumns().size()); //$NON-NLS-1$
        }
    }
}
