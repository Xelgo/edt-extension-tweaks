package ru.xelgo.edt.contextlinks.core;

import java.util.Collection;
import java.util.List;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.util.Pair;
import org.eclipse.xtext.util.Triple;

import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/** Keeps EDT's member resolution and environment/export filters, adding selected donor types as candidates. */
@Singleton
public class ContextLinksDynamicFeatureAccessComputer extends DynamicFeatureAccessComputer
{
    @Inject
    private ContextLinksMemberTypes memberTypes;

    @Override
    public List<FeatureEntry> getObjectFromContextDefOfTypes(Collection<TypeItem> types, Environments environments,
        String name, int parameterCount, boolean resolve, Resource resource, CancelIndicator cancel)
    {
        return super.getObjectFromContextDefOfTypes(memberTypes.expand(types, resource), environments,
            name, parameterCount, resolve, resource, cancel);
    }

    @Override
    public List<FeatureEntry> getObjectFromContextDefOfTypes(Collection<TypeItem> types, Environments environments,
        String name, boolean resolve, Resource resource, CancelIndicator cancel)
    {
        return super.getObjectFromContextDefOfTypes(memberTypes.expand(types, resource), environments,
            name, resolve, resource, cancel);
    }

    @Override
    public Collection<Triple<Collection<Method>, Collection<Property>, TypeItem>> getAllMethodAndProperties(
        List<TypeItem> types, Resource resource)
    {
        return super.getAllMethodAndProperties(memberTypes.expand(types, resource), resource);
    }

    @Override
    public Collection<Pair<Collection<Method>, TypeItem>> getAllMethods(Collection<TypeItem> types, Resource resource)
    {
        return super.getAllMethods(memberTypes.expand(types, resource), resource);
    }

    @Override
    public Collection<Pair<Collection<Property>, TypeItem>> getAllProperties(Collection<TypeItem> types,
        Resource resource)
    {
        return super.getAllProperties(memberTypes.expand(types, resource), resource);
    }

}
