package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.Field;
import java.util.Iterator;
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
    private ContextLinksQueryWizardPatches()
    {
        // Utility class.
    }

    public static boolean equalsDbViewFromQueryNamesOrLogical(DbViewElement left, DbViewElement right)
    {
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
            ContextLinks.logWarning("EDT Extension Tweaks QW adoption skip foreign extension object=" + describe(object)); //$NON-NLS-1$
        }

        if (removed > 0)
            ContextLinks.logWarning("EDT Extension Tweaks QW adoption filtered foreignExtensionObjects=" + removed //$NON-NLS-1$
                + " remaining=" + objects.size()); //$NON-NLS-1$
        return objects;
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
