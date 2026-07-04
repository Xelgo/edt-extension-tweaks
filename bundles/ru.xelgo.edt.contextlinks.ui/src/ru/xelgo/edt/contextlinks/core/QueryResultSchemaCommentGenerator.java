package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.wiring.ServiceAccess;

/**
 * Builds a BSL comment with query result columns by reusing EDT Query Wizard internals through reflection.
 */
public final class QueryResultSchemaCommentGenerator
{
    private static final String QW_UI_BUNDLE = "com._1c.g5.v8.dt.qw.ui"; //$NON-NLS-1$
    private static final String QUERY_SCHEMA_BUILDER =
        "com._1c.g5.v8.dt.qw.ui.utils.QuerySchemaBuilder"; //$NON-NLS-1$
    private static final String QUERY_WIZARD_SOURCE =
        "com._1c.g5.v8.dt.qw.ui.utils.QueryWizardSource"; //$NON-NLS-1$
    private static final String QUERY_TEXT_RESULT_PROCESSING =
        "com._1c.g5.v8.dt.qw.ui.utils.QueryTextResultProcessing"; //$NON-NLS-1$
    private static final String QUERY_SCHEMA_SELECT_QUERY =
        "com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery"; //$NON-NLS-1$
    private static final String DB_VIEW_FIELD_DEF =
        "com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef"; //$NON-NLS-1$
    private static final String TYPE_SEPARATOR = "|"; //$NON-NLS-1$
    private static final String REGION_NAME = "edt_tweaks_query_result_1"; //$NON-NLS-1$
    private static final String QUERY_VARIABLE = "\u0417\u0430\u043f\u0440\u043e\u0441"; //$NON-NLS-1$
    private static final String RESULT_VARIABLE =
        "\u0420\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442\u0417\u0430\u043f\u0440\u043e\u0441\u0430"; //$NON-NLS-1$
    private static final String SELECTION_VARIABLE =
        "\u0412\u044b\u0431\u043e\u0440\u043a\u0430"; //$NON-NLS-1$
    private static final String PACKAGE_RESULTS_VARIABLE =
        "\u0420\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442\u044b\u0417\u0430\u043f\u0440\u043e\u0441\u0430"; //$NON-NLS-1$
    private static final String PACKAGE_VARIABLE_PREFIX =
        "\u041f\u0430\u043a\u0435\u0442"; //$NON-NLS-1$

    private QueryResultSchemaCommentGenerator()
    {
        // Utility class.
    }

    public static String generate(String queryText, IProject project)
        throws QueryResultSchemaCommentException
    {
        if (queryText == null || queryText.isBlank())
            throw new QueryResultSchemaCommentException("Query text is empty"); //$NON-NLS-1$
        if (project == null)
            throw new QueryResultSchemaCommentException("Project is not resolved"); //$NON-NLS-1$

        Object builder = createQuerySchemaBuilder(project);
        Object schema = invoke(builder, "buildQuerySchema", new Class<?>[] { String.class }, queryText); //$NON-NLS-1$
        if (schema == null)
            throw new QueryResultSchemaCommentException("Query schema was not built"); //$NON-NLS-1$

        Object source = createQueryWizardSource(schema, project);
        applyAvailableTables(builder, source);

        List<ResultTable> results = extractResults(schema, source);
        if (results.isEmpty())
            throw new QueryResultSchemaCommentException("Query has no result fields"); //$NON-NLS-1$

        return renderComment(results, ContextLinksPreferences.getQueryResultContextModel());
    }

    private static Object createQuerySchemaBuilder(IProject project)
        throws QueryResultSchemaCommentException
    {
        Class<?> builderClass = loadQwClass(QUERY_SCHEMA_BUILDER);
        try
        {
            Constructor<?> constructor = builderClass.getConstructor(IProject.class, boolean.class);
            return constructor.newInstance(project, Boolean.FALSE);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            throw new QueryResultSchemaCommentException("Failed to create QuerySchemaBuilder", e); //$NON-NLS-1$
        }
    }

    private static Object createQueryWizardSource(Object schema, IProject project)
        throws QueryResultSchemaCommentException
    {
        Class<?> sourceClass = loadQwClass(QUERY_WIZARD_SOURCE);
        Constructor<?> constructor = findConstructor(sourceClass, 4);
        if (constructor == null)
            throw new QueryResultSchemaCommentException("QueryWizardSource constructor not found"); //$NON-NLS-1$

        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object v8ProjectManager = ServiceAccess.get(parameterTypes[2]);
        Object bmModelManager = ServiceAccess.get(parameterTypes[3]);
        if (v8ProjectManager == null || bmModelManager == null)
            throw new QueryResultSchemaCommentException("EDT project services are unavailable"); //$NON-NLS-1$

        try
        {
            return constructor.newInstance(schema, project, v8ProjectManager, bmModelManager);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            throw new QueryResultSchemaCommentException("Failed to create QueryWizardSource", e); //$NON-NLS-1$
        }
    }

    private static Constructor<?> findConstructor(Class<?> type, int parameterCount)
    {
        for (Constructor<?> constructor : type.getConstructors())
        {
            if (constructor.getParameterCount() == parameterCount)
                return constructor;
        }
        return null;
    }

    private static void applyAvailableTables(Object builder, Object source)
    {
        try
        {
            Object availableTables = invoke(builder, "getAvailableTables"); //$NON-NLS-1$
            if (availableTables instanceof List<?>)
                invoke(source, "setAvailableTables", new Class<?>[] { List.class }, availableTables); //$NON-NLS-1$
        }
        catch (QueryResultSchemaCommentException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks query schema comment ignored available tables: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    private static List<ResultTable> extractResults(Object schema, Object source)
        throws QueryResultSchemaCommentException
    {
        Object queries = invoke(schema, "getQueries"); //$NON-NLS-1$
        if (!(queries instanceof Iterable<?>))
            return List.of();

        List<ResultTable> results = new ArrayList<>();
        int queryIndex = 0;
        for (Object query : (Iterable<?>)queries)
        {
            if (!isInstanceOf(query, QUERY_SCHEMA_SELECT_QUERY))
            {
                queryIndex++;
                continue;
            }

            Object operators = invoke(query, "getOperators"); //$NON-NLS-1$
            if (operators instanceof Iterable<?>)
                collectQueryResult(source, results, queryIndex, (Iterable<?>)operators);
            queryIndex++;
        }
        return results;
    }

    private static void collectQueryResult(Object source, List<ResultTable> results, int queryIndex,
        Iterable<?> operators)
        throws QueryResultSchemaCommentException
    {
        for (Object operator : operators)
        {
            if (isTemporaryTableOperator(operator))
                return;

            Object selectFields = invoke(operator, "getSelectFields"); //$NON-NLS-1$
            if (!(selectFields instanceof List<?> fields) || fields.isEmpty())
                continue;

            List<Column> columns = extractColumns(source, fields);
            if (!columns.isEmpty())
                results.add(new ResultTable(queryIndex, columns));
            return;
        }
    }

    private static boolean isTemporaryTableOperator(Object operator)
        throws QueryResultSchemaCommentException
    {
        return invoke(operator, "getPlacementTable") != null || invoke(operator, "getAddTempTable") != null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<Column> extractColumns(Object source, List<?> fields)
        throws QueryResultSchemaCommentException
    {
        Map<Object, Object> presentations = presentationsByExpression(source, fields);
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++)
        {
            Object field = fields.get(i);
            String name = columnName(field, presentations.get(field));
            if (name == null || name.isBlank())
                name = "Column" + (i + 1); //$NON-NLS-1$

            String type = typeName(field, source, presentations.get(field));
            columns.add(new Column(name, type));
        }
        return columns;
    }

    private static Map<Object, Object> presentationsByExpression(Object source, List<?> fields)
    {
        try
        {
            Class<?> processingClass = loadQwClass(QUERY_TEXT_RESULT_PROCESSING);
            Method method = processingClass.getMethod("getAllPresentations", source.getClass(), List.class); //$NON-NLS-1$
            Object value = method.invoke(null, source, fields);
            if (!(value instanceof Iterable<?> presentations))
                return Map.of();

            Map<Object, Object> result = new IdentityHashMap<>();
            for (Object presentation : presentations)
            {
                Object expression = field(presentation, "expression"); //$NON-NLS-1$
                if (expression != null)
                    result.put(expression, presentation);
            }
            return result;
        }
        catch (QueryResultSchemaCommentException | ReflectiveOperationException | RuntimeException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks query schema comment presentations unavailable: " //$NON-NLS-1$
                + e.getMessage());
            return Map.of();
        }
    }

    private static String columnName(Object field, Object presentation)
    {
        String alias = safeString(invokeQuietly(field, "getAlias")); //$NON-NLS-1$
        if (alias != null && !alias.isBlank())
            return alias;

        Object fieldDbView = presentation != null ? field(presentation, "fieldDbView") : null; //$NON-NLS-1$
        String viewName = namedElementName(fieldDbView);
        if (viewName != null && !viewName.isBlank())
            return viewName;

        Object expression = invokeQuietly(field, "getExpression"); //$NON-NLS-1$
        String expressionName = namedElementName(expression);
        if (expressionName != null && !expressionName.isBlank())
            return expressionName;

        return null;
    }

    private static String typeName(Object field, Object source, Object presentation)
    {
        Object typeDescription = invokeQuietly(field, "getType", //$NON-NLS-1$
            new Class<?>[] { parameterType(source, "getTypeChecker") }, invokeQuietly(source, "getTypeChecker")); //$NON-NLS-1$
        String type = describeType(typeDescription);
        if (type != null)
            return type;

        Object fieldDbView = presentation != null ? field(presentation, "fieldDbView") : null; //$NON-NLS-1$
        if (fieldDbView != null && isInstanceOf(fieldDbView, DB_VIEW_FIELD_DEF))
        {
            type = describeType(invokeQuietly(fieldDbView, "getType")); //$NON-NLS-1$
            if (type != null)
                return type;
        }

        Object derivedTypeComputer = invokeQuietly(source, "getDerivedTypeDbViewComputer"); //$NON-NLS-1$
        if (derivedTypeComputer != null && fieldDbView != null)
        {
            Object pair = invokeQuietly(derivedTypeComputer, "getFieldType", //$NON-NLS-1$
                new Class<?>[] { Object.class }, fieldDbView);
            Object first = pair != null ? invokeQuietly(pair, "getFirst") : null; //$NON-NLS-1$
            return describeType(first);
        }

        return null;
    }

    private static Class<?> parameterType(Object target, String methodName)
    {
        if (target == null)
            return Object.class;
        for (Method method : target.getClass().getMethods())
        {
            if (methodName.equals(method.getName()) && method.getParameterCount() == 0)
                return method.getReturnType();
        }
        return Object.class;
    }

    private static String describeType(Object typeDescription)
    {
        Object types = invokeQuietly(typeDescription, "getTypes"); //$NON-NLS-1$
        if (!(types instanceof Iterable<?> iterable))
            return null;

        Set<String> names = new LinkedHashSet<>();
        for (Object typeItem : iterable)
        {
            String name = namedElementName(typeItem);
            if (name != null && !name.isBlank())
                names.add(name);
        }

        if (names.isEmpty())
            return null;
        return names.stream().collect(Collectors.joining(TYPE_SEPARATOR));
    }

    private static String namedElementName(Object object)
    {
        String nameRu = safeString(invokeQuietly(object, "getNameRu")); //$NON-NLS-1$
        if (nameRu != null && !nameRu.isBlank())
            return nameRu;

        String name = safeString(invokeQuietly(object, "getName")); //$NON-NLS-1$
        return name != null && !name.isBlank() ? name : null;
    }

    private static String renderComment(List<ResultTable> results,
        ContextLinksPreferences.QueryResultContextModel model)
    {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("#\u041e\u0431\u043b\u0430\u0441\u0442\u044c ") //$NON-NLS-1$
            .append(REGION_NAME)
            .append(lineSeparator)
            .append(lineSeparator);

        if (results.size() == 1 && results.get(0).queryIndex == 0)
            renderSingleResult(builder, results.get(0), model, lineSeparator);
        else
            renderPackageResults(builder, results, model, lineSeparator);

        builder.append(lineSeparator)
            .append("#\u041a\u043e\u043d\u0435\u0446\u041e\u0431\u043b\u0430\u0441\u0442\u0438"); //$NON-NLS-1$
        return builder.toString();
    }

    private static void renderSingleResult(StringBuilder builder, ResultTable result,
        ContextLinksPreferences.QueryResultContextModel model, String lineSeparator)
    {
        String variableName = model == ContextLinksPreferences.QueryResultContextModel.SELECTION
            ? SELECTION_VARIABLE : RESULT_VARIABLE;
        String methodName = model == ContextLinksPreferences.QueryResultContextModel.SELECTION
            ? "\u0412\u044b\u0431\u0440\u0430\u0442\u044c" : "\u0412\u044b\u0433\u0440\u0443\u0437\u0438\u0442\u044c"; //$NON-NLS-1$ //$NON-NLS-2$
        builder.append('\t')
            .append(variableName)
            .append(" = ") //$NON-NLS-1$
            .append(QUERY_VARIABLE)
            .append(".\u0412\u044b\u043f\u043e\u043b\u043d\u0438\u0442\u044c().") //$NON-NLS-1$
            .append(methodName)
            .append("();") //$NON-NLS-1$
            .append(lineSeparator);
        renderResultComment(builder, variableName, result, lineSeparator);
    }

    private static void renderPackageResults(StringBuilder builder, List<ResultTable> results,
        ContextLinksPreferences.QueryResultContextModel model, String lineSeparator)
    {
        String methodName = model == ContextLinksPreferences.QueryResultContextModel.SELECTION
            ? "\u0412\u044b\u0431\u0440\u0430\u0442\u044c" : "\u0412\u044b\u0433\u0440\u0443\u0437\u0438\u0442\u044c"; //$NON-NLS-1$ //$NON-NLS-2$
        builder.append('\t')
            .append(PACKAGE_RESULTS_VARIABLE)
            .append(" = ") //$NON-NLS-1$
            .append(QUERY_VARIABLE)
            .append(".\u0412\u044b\u043f\u043e\u043b\u043d\u0438\u0442\u044c\u041f\u0430\u043a\u0435\u0442();") //$NON-NLS-1$
            .append(lineSeparator);

        for (int i = 0; i < results.size(); i++)
        {
            ResultTable result = results.get(i);
            String variableName = PACKAGE_VARIABLE_PREFIX + (i + 1);
            builder.append('\t')
                .append(variableName)
                .append(" = ") //$NON-NLS-1$
                .append(PACKAGE_RESULTS_VARIABLE)
                .append("[") //$NON-NLS-1$
                .append(result.queryIndex)
                .append("].") //$NON-NLS-1$
                .append(methodName)
                .append("();") //$NON-NLS-1$
                .append(lineSeparator);
            renderResultComment(builder, variableName, result, lineSeparator);
            if (i + 1 < results.size())
                builder.append(lineSeparator);
        }
    }

    private static void renderResultComment(StringBuilder builder, String variableName, ResultTable result,
        String lineSeparator)
    {
        builder.append('\t')
            .append("// ") //$NON-NLS-1$
            .append(variableName)
            .append(lineSeparator);
        for (Column column : result.columns)
        {
            builder.append('\t')
                .append("// * ") //$NON-NLS-1$
                .append(commentText(column.name))
                .append(" - "); //$NON-NLS-1$
            if (column.type != null && !column.type.isBlank())
                builder.append(commentText(column.type)).append(" - "); //$NON-NLS-1$
            builder.append(lineSeparator);
        }
    }

    private static String commentText(String value)
    {
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static Object invoke(Object target, String methodName)
        throws QueryResultSchemaCommentException
    {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
        throws QueryResultSchemaCommentException
    {
        if (target == null)
            return null;
        try
        {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        }
        catch (NoSuchMethodException e)
        {
            Method method = findCompatibleMethod(target.getClass(), methodName, arguments);
            if (method == null)
                throw new QueryResultSchemaCommentException("Method not found: " + methodName, e); //$NON-NLS-1$
            return invokeCompatible(target, method, arguments);
        }
        catch (IllegalAccessException | InvocationTargetException | RuntimeException e)
        {
            throw new QueryResultSchemaCommentException("Method failed: " + methodName, e); //$NON-NLS-1$
        }
    }

    private static Object invokeCompatible(Object target, Method method, Object... arguments)
        throws QueryResultSchemaCommentException
    {
        try
        {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        }
        catch (IllegalAccessException | InvocationTargetException | RuntimeException e)
        {
            throw new QueryResultSchemaCommentException("Method failed: " + method.getName(), e); //$NON-NLS-1$
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, Object... arguments)
    {
        for (Method method : type.getMethods())
        {
            if (!methodName.equals(method.getName()) || method.getParameterCount() != arguments.length)
                continue;

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < arguments.length; i++)
            {
                if (arguments[i] != null && !parameterTypes[i].isInstance(arguments[i]))
                {
                    compatible = false;
                    break;
                }
            }
            if (compatible)
                return method;
        }
        return null;
    }

    private static Object invokeQuietly(Object target, String methodName)
    {
        try
        {
            return invoke(target, methodName);
        }
        catch (QueryResultSchemaCommentException e)
        {
            return null;
        }
    }

    private static Object invokeQuietly(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
    {
        try
        {
            return invoke(target, methodName, parameterTypes, arguments);
        }
        catch (QueryResultSchemaCommentException e)
        {
            return null;
        }
    }

    private static Object field(Object target, String name)
    {
        if (target == null)
            return null;

        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
            catch (IllegalAccessException | RuntimeException e)
            {
                return null;
            }
        }
        return null;
    }

    private static String safeString(Object value)
    {
        return value instanceof String ? (String)value : null;
    }

    private static boolean isInstanceOf(Object object, String className)
    {
        if (object == null)
            return false;

        Class<?> type = object.getClass();
        while (type != null)
        {
            if (className.equals(type.getName()))
                return true;
            for (Class<?> interfaceType : type.getInterfaces())
            {
                if (isInterfaceOrParent(interfaceType, className))
                    return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean isInterfaceOrParent(Class<?> type, String className)
    {
        if (type == null)
            return false;
        if (className.equals(type.getName()))
            return true;
        for (Class<?> parent : type.getInterfaces())
        {
            if (isInterfaceOrParent(parent, className))
                return true;
        }
        return isInterfaceOrParent(type.getSuperclass(), className);
    }

    private static Class<?> loadQwClass(String className)
        throws QueryResultSchemaCommentException
    {
        Bundle current = FrameworkUtil.getBundle(QueryResultSchemaCommentGenerator.class);
        if (current == null || current.getBundleContext() == null)
            throw new QueryResultSchemaCommentException("Current bundle context is unavailable"); //$NON-NLS-1$

        for (Bundle bundle : current.getBundleContext().getBundles())
        {
            if (!QW_UI_BUNDLE.equals(bundle.getSymbolicName()))
                continue;

            try
            {
                return bundle.loadClass(className);
            }
            catch (ClassNotFoundException e)
            {
                throw new QueryResultSchemaCommentException("Class not found: " + className, e); //$NON-NLS-1$
            }
        }

        throw new QueryResultSchemaCommentException("Query Wizard UI bundle is unavailable"); //$NON-NLS-1$
    }

    private static final class ResultTable
    {
        private final int queryIndex;
        private final List<Column> columns;

        ResultTable(int queryIndex, List<Column> columns)
        {
            this.queryIndex = queryIndex;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        }
    }

    private static final class Column
    {
        private final String name;
        private final String type;

        Column(String name, String type)
        {
            this.name = Objects.requireNonNull(name);
            this.type = type;
        }
    }

    public static final class QueryResultSchemaCommentException
        extends Exception
    {
        private static final long serialVersionUID = 1L;

        QueryResultSchemaCommentException(String message)
        {
            super(message);
        }

        QueryResultSchemaCommentException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
