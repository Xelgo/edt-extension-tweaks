package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.dbview.DbViewElement;
import com._1c.g5.v8.dt.metadata.dbview.ExtendedDbViewFieldTableDef;
import com._1c.g5.v8.dt.metadata.dbview.ExtendedDbViewTableDef;

/**
 * Helper methods called from woven EDT Query Wizard classes.
 */
public final class ContextLinksQueryWizardPatches
{
    private static final int AVAILABLE_TABLE_TEMP_TABLES_TYPE = 0;
    private static final long RECENT_NESTED_TEMP_TABLES_TTL_MS = 120_000L;
    private static volatile List<DbViewElement> recentNestedTempTables = List.of();
    private static volatile long recentNestedTempTablesTimestamp;

    private ContextLinksQueryWizardPatches()
    {
        // Utility class.
    }

    public static boolean equalsDbViewFromQueryNamesOrLogical(DbViewElement left, DbViewElement right)
    {
        if (!ContextLinksPreferences.isQueryWizardEnabled())
            return equalsDbViewFromQueryNames(left, right);

        if (equalsDbViewFromQueryNames(left, right))
            return true;

        DbViewElement normalizedLeft = normalizeExtendedDbView(left);
        DbViewElement normalizedRight = normalizeExtendedDbView(right);
        if (Objects.equals(normalizedLeft, normalizedRight))
            return true;

        return sameDbViewName(normalizedLeft, normalizedRight) || sameDbViewName(left, right);
    }

    public static Set<EObject> filterObjectsToAdopt(Set<EObject> objects, Object adoptSupport)
    {
        if (!ContextLinksPreferences.isQueryWizardEnabled())
            return objects;

        if (objects == null || objects.isEmpty() || adoptSupport == null)
            return objects;

        IExtensionProject currentExtension = field(adoptSupport, "extensionProject", IExtensionProject.class); //$NON-NLS-1$
        IV8ProjectManager projectManager = field(adoptSupport, "v8ProjectManager", IV8ProjectManager.class); //$NON-NLS-1$
        if (currentExtension == null || projectManager == null)
            return objects;

        int removed = 0;
        for (Iterator<EObject> iterator = objects.iterator(); iterator.hasNext();)
        {
            EObject object = iterator.next();
            if (!isForeignExtensionObject(object, currentExtension, projectManager))
                continue;

            iterator.remove();
            removed++;
            ContextLinks.logDebug("EDT Extension Tweaks QW adoption skip foreign extension object=" + describe(object)); //$NON-NLS-1$
        }

        if (removed > 0)
            ContextLinks.logDebug("EDT Extension Tweaks QW adoption filtered foreignExtensionObjects=" + removed //$NON-NLS-1$
                + " remaining=" + objects.size()); //$NON-NLS-1$
        return objects;
    }

    public static void rememberQlEditorQuerySource(Object qlEditor, Object queryWizardSource)
    {
        if (!ContextLinksPreferences.isQueryWizardNestedTempTablesEnabled())
            return;

        if (qlEditor == null || queryWizardSource == null)
            return;

        List<DbViewElement> tempTables = extractTempTables(queryWizardSource);
        if (tempTables.isEmpty())
        {
            clearRecentNestedTempTables();
            return;
        }

        recentNestedTempTables = List.copyOf(tempTables);
        recentNestedTempTablesTimestamp = System.currentTimeMillis();
    }

    public static void applyPendingNestedTempTables(Object queryWizardControl)
    {
        List<DbViewElement> tempTables = consumeRecentNestedTempTables();
        if (!ContextLinksPreferences.isQueryWizardNestedTempTablesEnabled() || queryWizardControl == null
            || tempTables.isEmpty())
        {
            return;
        }

        try
        {
            Method method = queryWizardControl.getClass().getDeclaredMethod("addTempTablesForNestedQuery", List.class); //$NON-NLS-1$
            method.setAccessible(true);
            method.invoke(queryWizardControl, new ArrayList<>(tempTables));
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks failed to apply nested Query Wizard temp tables: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    private static DbViewElement normalizeExtendedDbView(DbViewElement dbView)
    {
        if (dbView instanceof ExtendedDbViewTableDef extended && extended.getSource() != null)
            return extended.getSource();
        if (dbView instanceof ExtendedDbViewFieldTableDef extended && extended.getSource() != null)
            return extended.getSource();
        return dbView;
    }

    private static boolean equalsDbViewFromQueryNames(DbViewElement left, DbViewElement right)
    {
        return hasClassName(left, "com._1c.g5.v8.dt.ql.model.DbViewFromQuery") //$NON-NLS-1$
            && hasClassName(right, "com._1c.g5.v8.dt.ql.model.DbViewFromQuery") //$NON-NLS-1$
            && equalsIgnoreCase(left.getName(), right.getName());
    }

    private static boolean sameDbViewName(DbViewElement left, DbViewElement right)
    {
        if (left == null || right == null)
            return false;

        return (left.getName() != null && right.getName() != null && equalsIgnoreCase(left.getName(), right.getName()))
            || (left.getNameRu() != null && right.getNameRu() != null
                && equalsIgnoreCase(left.getNameRu(), right.getNameRu()));
    }

    private static boolean isForeignExtensionObject(EObject object, IExtensionProject currentExtension,
        IV8ProjectManager projectManager)
    {
        try
        {
            IV8Project ownerProject = projectManager.getProject(object);
            return ownerProject instanceof IExtensionProject && !ownerProject.equals(currentExtension);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean equalsIgnoreCase(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean hasClassName(Object object, String className)
    {
        return object != null && className.equals(object.getClass().getName());
    }

    private static List<DbViewElement> extractTempTables(Object queryWizardSource)
    {
        Object availableTables = invoke(queryWizardSource, "getAvailableTables"); //$NON-NLS-1$
        if (!(availableTables instanceof Iterable<?> iterable))
            return List.of();

        List<DbViewElement> tempTables = new ArrayList<>();
        for (Object availableTable : iterable)
        {
            Object type = invoke(availableTable, "getType"); //$NON-NLS-1$
            if (!(type instanceof Integer) || ((Integer)type).intValue() != AVAILABLE_TABLE_TEMP_TABLES_TYPE)
                continue;

            Object dbViews = invokeCompatible(availableTable, "getDbViews", //$NON-NLS-1$
                queryWizardSource);
            if (!(dbViews instanceof Iterable<?> dbViewIterable))
                return List.of();

            for (Object dbView : dbViewIterable)
            {
                if (dbView instanceof DbViewElement dbViewElement)
                    tempTables.add(dbViewElement);
            }
            return tempTables;
        }

        return List.of();
    }

    private static Object invoke(Object target, String name)
    {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
    {
        if (target == null)
            return null;

        try
        {
            Method method = target.getClass().getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private static Object invokeCompatible(Object target, String name, Object... arguments)
    {
        if (target == null)
            return null;

        try
        {
            for (Method method : target.getClass().getMethods())
            {
                if (!name.equals(method.getName()) || method.getParameterCount() != arguments.length)
                    continue;

                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < parameterTypes.length; i++)
                {
                    if (arguments[i] != null && !parameterTypes[i].isInstance(arguments[i]))
                    {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible)
                    continue;

                method.setAccessible(true);
                return method.invoke(target, arguments);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
        return null;
    }

    private static List<DbViewElement> consumeRecentNestedTempTables()
    {
        if (recentNestedTempTables == null || recentNestedTempTables.isEmpty())
            return List.of();

        long timestamp = recentNestedTempTablesTimestamp;
        if (timestamp <= 0L || System.currentTimeMillis() - timestamp > RECENT_NESTED_TEMP_TABLES_TTL_MS)
        {
            clearRecentNestedTempTables();
            return List.of();
        }

        List<DbViewElement> tempTables = recentNestedTempTables;
        clearRecentNestedTempTables();
        return tempTables;
    }

    private static void clearRecentNestedTempTables()
    {
        recentNestedTempTables = List.of();
        recentNestedTempTablesTimestamp = 0L;
    }

    private static <T> T field(Object target, String name, Class<T> type)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(target);
            return type.isInstance(value) ? type.cast(value) : null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private static String describe(EObject object)
    {
        if (object == null)
            return "NULL"; //$NON-NLS-1$
        String type = object.eClass() != null ? object.eClass().getName() : object.getClass().getName();
        String uri = object.eResource() != null ? object.eResource().getURIFragment(object) : "NO_RESOURCE"; //$NON-NLS-1$
        return type + "{" + uri + "}"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
