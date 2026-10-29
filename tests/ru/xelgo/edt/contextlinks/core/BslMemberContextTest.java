package ru.xelgo.edt.contextlinks.core;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.EObjectDescription;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.impl.SimpleScope;
import org.eclipse.xtext.util.CancelIndicator;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.BslContextDefFactory;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;

/** Uses EDT's actual completion and member resolver, not a second implementation of their lookup rules. */
public class BslMemberContextTest
{
    private final McoreFactory factory = McoreFactory.eINSTANCE;
    private final ResourceSetImpl resources = new ResourceSetImpl();
    private final Resource consumer = resource("consumer", "module.bsl");
    private final List<IScope> donors = new ArrayList<>();
    private final ContextLinksMemberTypes types = new ContextLinksMemberTypes()
    {
        @Override protected List<IScope> linkedScopes(Resource resource)
        {
            assertSame(consumer, resource);
            return donors;
        }
    };

    public BslMemberContextTest() throws Exception
    {
        consumer.getContents().add(BslFactory.eINSTANCE.createModule());
        set(types, ContextLinksMemberTypes.class, "names", new IQualifiedNameConverter.DefaultImpl());
    }

    @Test
    public void nativeResolverSeesExtensionAttributeOnAlreadyResolvedBaseObject() throws Exception
    {
        Type base = type("cf", "CatalogObject.Contracts");
        Type linked = type("extension", "CatalogObject.Contracts");
        Property added = property(linked, "мм_Проект");
        donors.add(scope(linked));
        var computer = computer();
        // Baseline reproduces the missing member. No global type-name lookup is performed here.
        assertTrue(new DynamicFeatureAccessComputer().getAllProperties(List.of(base), consumer)
            .stream().allMatch(row -> row.getFirst().isEmpty()));
        var entries = computer.getObjectFromContextDefOfTypes(List.of(base), Environments.SERVER,
            "ММ_Проект", true, consumer, CancelIndicator.NullImpl);
        assertEquals(1, entries.size());
        assertSame(added, entries.get(0).getFeature());
        assertSame(added.getTypeContainer(), ((Property)entries.get(0).getFeature()).getTypeContainer());
        assertSame(linked, computer.getCorrespondingType(List.of(base), Environments.SERVER,
            "ММ_Проект", consumer));
        assertTrue(base.getContextDef().getProperties().isEmpty());
        assertSame(linked.getContextDef(), added.eContainer());
    }

    @Test
    public void completionIncludesBaseAndSeveralExtensionsAndKeepsTheirOwners() throws Exception
    {
        Type base = type("cf", "CatalogObject.Contracts");
        Property nativeProperty = property(base, "Code");
        Type first = type("extension1", "CatalogObject.Contracts");
        Property added = property(first, "мм_Проект");
        Type second = type("extension2", "CatalogObject.Contracts");
        Property another = property(second, "AnotherAttribute");
        donors.add(scope(first, second));
        var rows = computer().getAllMethodAndProperties(List.of(base), consumer);
        List<Property> properties = rows.stream().flatMap(row -> row.getSecond().stream()).toList();
        assertEquals(List.of(nativeProperty, added, another), properties);
        assertEquals(List.of(nativeProperty), base.getContextDef().getProperties());
        assertSame(first.getContextDef(), added.eContainer());
    }

    @Test
    public void nativeResolverSeesExportedManagerMethodAndFiltersEnvironment() throws Exception
    {
        Type base = type("cf", "CatalogManager.Contracts");
        Type linked = type("extension", "CatalogManager.Contracts");
        var method = factory.createMethod();
        method.setName("ММ_Тест");
        method.setNameRu("ММ_Тест");
        method.setEnvironments(Environments.SERVER);
        linked.getContextDef().getMethods().add(method);
        donors.add(scope(linked));
        var computer = computer();
        var entries = computer.getObjectFromContextDefOfTypes(List.of(base), Environments.SERVER,
            "ММ_Тест", 0, consumer, CancelIndicator.NullImpl);
        assertEquals(1, entries.size());
        assertSame(method, entries.get(0).getFeature());
        assertTrue(computer.getObjectFromContextDefOfTypes(List.of(base), Environments.MNG_CLIENTS,
            "ММ_Тест", 0, consumer, CancelIndicator.NullImpl).isEmpty());
        assertTrue(base.getContextDef().getMethods().isEmpty());
    }

    @Test
    public void onlySelectedSameNamedTypesAreAddedAndDuplicateUrisAreIgnored()
    {
        Type base = type("cf", "CatalogObject.Contracts");
        Type selected = type("selected", "CatalogObject.Contracts");
        Type other = type("selected", "CatalogObject.Other");
        Type manager = type("selected", "CatalogManager.Contracts");
        type("notSelected", "CatalogObject.Contracts");
        donors.add(scope(base, selected, other, manager));
        donors.add(scope(selected));
        assertEquals(List.of(base, selected), types.expand(List.of(base), consumer));
        assertEquals(List.of(base, selected), types.expand(List.of(base, selected), consumer));
    }

    @Test
    public void exportedManagerMethodInReferencedModuleContextRemainsResolvable() throws Exception
    {
        Type base = type("cf", "CatalogManager.Contracts");
        Type linked = type("extension", "CatalogManager.Contracts");
        var moduleContext = BslContextDefFactory.eINSTANCE.createBslContextDef();
        var method = BslContextDefFactory.eINSTANCE.createBslContextDefMethod();
        method.setName("ММ_ЭкспортнаяПроцедура");
        method.setNameRu("ММ_ЭкспортнаяПроцедура");
        method.setEnvironments(Environments.SERVER);
        method.setSourceUri(URI.createURI("memory:/extension/ManagerModule.bsl#method"));
        moduleContext.getMethods().add(method);
        linked.getContextDef().getRefContextDefs().add(moduleContext);
        donors.add(scope(linked));
        var computer = computer();
        var entries = computer.getObjectFromContextDefOfTypes(List.of(base), Environments.SERVER,
            method.getName(), 0, consumer, CancelIndicator.NullImpl);
        assertEquals(1, entries.size());
        assertSame(method, entries.get(0).getFeature());
        assertSame(linked, computer.getCorrespondingType(List.of(base), Environments.SERVER,
            method.getName(), 0, consumer));
        assertTrue(computer.getAllMethodAndProperties(List.of(base), consumer).stream()
            .anyMatch(row -> row.getFirst().contains(method)));
    }

    @Test
    public void donorProxyResolvesInConsumerResourceSet()
    {
        Type base = type("cf", "CatalogRef.Contracts");
        Type linked = type("extension", "CatalogRef.Contracts");
        Type proxy = factory.createType();
        ((org.eclipse.emf.ecore.InternalEObject)proxy).eSetProxyURI(EcoreUtil.getURI(linked));
        donors.add(new SimpleScope(List.of(EObjectDescription.create(
            QualifiedName.create("CatalogRef", "Contracts"), proxy))));
        assertEquals(List.of(base, linked), types.expand(List.of(base), consumer));
    }

    @Test
    public void missingDonorAndStandardTypesKeepNativeContext()
    {
        Type base = type("cf", "CatalogObject.Contracts");
        assertEquals(List.of(base), types.expand(List.of(base), consumer));
        Type standard = type("platform", "ValueTable");
        Type fake = type("extension", "ValueTable");
        donors.add(scope(fake));
        assertEquals(List.of(standard), types.expand(List.of(standard), consumer));
        assertEquals(List.of(base), types.expand(List.of(base), null));
    }

    private ContextLinksDynamicFeatureAccessComputer computer() throws Exception
    {
        var computer = new ContextLinksDynamicFeatureAccessComputer();
        set(computer, ContextLinksDynamicFeatureAccessComputer.class, "memberTypes", types);
        return computer;
    }

    private Type type(String project, String name)
    {
        Type type = factory.createType();
        type.setName(name);
        type.setNameRu(name);
        type.setEnvironments(Environments.ALL);
        type.setContextDef(factory.createContextDef());
        type.getContextDef().setEnvironments(Environments.ALL);
        resource(project, name).getContents().add(type);
        return type;
    }

    private Property property(Type owner, String name)
    {
        Property property = factory.createProperty();
        property.setName(name);
        property.setNameRu(name);
        property.setEnvironments(Environments.SERVER);
        property.setTypeContainer(factory.createTypeContainerRef());
        owner.getContextDef().getProperties().add(property);
        return property;
    }

    private Resource resource(String project, String path)
    {
        Resource resource = new ResourceImpl(URI.createURI("memory:/" + project + "/" + path));
        resources.getResources().add(resource);
        return resource;
    }

    private IScope scope(Type... types)
    {
        List<IEObjectDescription> descriptions = new ArrayList<>();
        for (Type type : types)
            descriptions.add(EObjectDescription.create(
                new IQualifiedNameConverter.DefaultImpl().toQualifiedName(type.getName()), type));
        return new SimpleScope(descriptions, true);
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
