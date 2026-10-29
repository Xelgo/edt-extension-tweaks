package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/** Adds donor variants at member lookup, even when a method returns an already resolved base type. */
@Singleton
public class ContextLinksMemberTypes
{
    @Inject
    private ContextLinksCachedScopeProvider scopes;

    @Inject
    private IResourceLookup resourceLookup;

    @Inject
    private IQualifiedNameConverter names;

    public List<TypeItem> expand(Collection<? extends TypeItem> types, Resource consumer)
    {
        List<TypeItem> result = new ArrayList<>(types);
        if (types.isEmpty() || consumer == null)
            return result;

        List<IScope> donors = null;
        Set<String> added = new HashSet<>();
        for (TypeItem type : types)
            added.add(EcoreUtil.getURI(type).toString());
        for (TypeItem type : types)
        {
            String name = McoreUtil.getTypeName(type);
            // Metadata types are qualified (e.g. CatalogObject.Contracts). Standard platform types stay native.
            if (name == null || name.indexOf('.') < 0)
                continue;
            if (donors == null)
                donors = linkedScopes(consumer);
            for (IScope scope : donors)
            {
                addVariants(result, added, type, name, scope, consumer);
                String nameRu = McoreUtil.getTypeNameRu(type);
                if (nameRu != null && !nameRu.equals(name))
                    addVariants(result, added, type, nameRu, scope, consumer);
            }
        }
        return result;
    }

    protected List<IScope> linkedScopes(Resource consumer)
    {
        return scopes.getLinkedMemberTypeScopes(resourceLookup.getProject(consumer));
    }

    private void addVariants(List<TypeItem> result, Set<String> added, TypeItem original, String name,
        IScope scope, Resource consumer)
    {
        for (IEObjectDescription description : scope.getElements(names.toQualifiedName(name)))
        {
            if (added.contains(description.getEObjectURI().toString()))
                continue;
            EObject object = EcoreUtil.resolve(description.getEObjectOrProxy(), consumer);
            if (!(object instanceof TypeItem variant) || variant.eIsProxy())
                continue;
            // Never mix an object with its reference/manager, or unrelated objects with similar names.
            if (!sameName(original, variant))
                continue;
            if (added.add(EcoreUtil.getURI(variant).toString()))
                result.add(variant);
        }
    }

    private static boolean sameName(TypeItem first, TypeItem second)
    {
        String name = McoreUtil.getTypeName(first);
        String other = McoreUtil.getTypeName(second);
        return name != null && name.equalsIgnoreCase(other);
    }
}
