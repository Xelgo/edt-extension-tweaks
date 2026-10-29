package ru.xelgo.edt.contextlinks.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.EObjectDescription;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.impl.SimpleScope;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldTableDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewPackage;
import com._1c.g5.v8.dt.metadata.dbview.DbViewTableDef;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.qw.ui.contentproviders.AvailableTablesContentProvider;

/** External processors must see added fields without changing the base table's metadata identity. */
public class ExternalQueryScopeTest
{
    private final QualifiedName name = QualifiedName.create("Catalog", "Contracts");
    private final DbViewFactory factory = DbViewFactory.eINSTANCE;

    @Test
    public void sameNamedLinkedTableKeepsBasePropertiesAndAddsFields() throws Exception
    {
        DbViewTableDef base = table("base");
        DbViewTableDef linked = table("extension");
        DbViewFieldDef code = field("Code");
        DbViewFieldDef extra = field("ExtensionFlag");
        extra.setType(McoreFactory.eINSTANCE.createTypeDescription());
        extra.setNameRu("ПризнакРасширения");
        base.getFields().add(code);
        linked.getFields().add(field("Code"));
        linked.getFields().add(extra);
        linked.setInvisible(true);
        IScope scope = composed(base, linked);
        IEObjectDescription description = scope.getSingleElement(name);
        DbViewTableDef result = (DbViewTableDef)description.getEObjectOrProxy();
        assertEquals(List.of("Code", "ExtensionFlag"), names(result.getFields()));
        assertSame(base.getMdObject(), result.getMdObject());
        assertSame(base.eClass(), result.eClass());
        assertSame(base.eResource(), result.eResource());
        assertSame(base.eContainer(), result.eContainer());
        assertEquals(EcoreUtil.getURI(base), EcoreUtil.getURI(result));
        assertSame(base.eContainmentFeature(), result.eContainmentFeature());
        assertEquals(base.getName(), result.getName());
        assertEquals(base.isInvisible(), result.isInvisible());
        assertEquals(EObjectDescription.create(name, base).getEObjectURI(), description.getEObjectURI());
        assertSame(code, result.getFields().get(0));
        assertSame(extra, result.getFields().get(1));
        assertSame(extra.getType(), result.getFields().get(1).getType());
        assertEquals("ПризнакРасширения", result.getFields().get(1).getNameRu());
        assertEquals("base", description.getUserData("origin"));
        assertEquals(List.of("Code", "ExtensionFlag"), names((List<?>)result.eGet(DbViewPackage.Literals.TABLE__FIELDS)));
        assertSame(description, scope.getSingleElement(name));
        assertSame(description, scope.getElements(linked).iterator().next());
        assertSame(description, scope.getAllElements().iterator().next());
        assertFalse(scope.getAllElements().iterator().next().getEObjectOrProxy().eIsProxy());
        assertEquals(1, size(scope.getAllElements()));
        // Context projection must not mutate the project's metadata or DB views.
        assertEquals(List.of("Code"), names(base.getFields()));
        assertTrue(linked.isInvisible());
    }

    @Test
    public void edtContentProviderGetsMergedFieldsForBothTableAndTabularSection() throws Exception
    {
        DbViewTableDef base = table("base");
        DbViewTableDef linked = table("extension");
        base.getFields().add(field("Code"));
        base.getFields().add(section("Items", "Quantity"));
        linked.getFields().add(field("ExtensionFlag"));
        linked.getFields().add(section("Items", "ExtraColumn"));
        DbViewTableDef result = (DbViewTableDef)composed(base, linked).getSingleElement(name).getEObjectOrProxy();
        AvailableTablesContentProvider provider = new AvailableTablesContentProvider(null);
        List<?> rows = List.of(provider.getChildren(result));
        assertEquals(List.of("Code", "Items", "ExtensionFlag"), names(rows));
        DbViewFieldTableDef items = (DbViewFieldTableDef)rows.get(1);
        assertEquals(List.of("Quantity", "ExtraColumn"), names(List.of(provider.getChildren(items))));
    }

    @Test
    public void allElementsBeforeNamedLookupGivesSameProjection() throws Exception
    {
        DbViewTableDef base = table("base");
        DbViewTableDef linked = table("extension");
        linked.getFields().add(field("Extra"));
        IScope scope = composed(base, linked);
        IEObjectDescription first = scope.getAllElements().iterator().next();
        assertSame(first, scope.getSingleElement(name));
        assertSame(first, scope.getSingleElement(base));
        assertEquals(List.of("Extra"), names(((DbViewTableDef)first.getEObjectOrProxy()).getFields()));
    }

    @Test
    public void mergesRealEdtExtendedViewWithoutExposingItsExtensionSpecificClass() throws Exception
    {
        DbViewTableDef base = table("base");
        base.getFields().add(field("Code"));
        var extended = factory.createExtendedDbViewTableDef();
        extended.setSource(base);
        extended.setCombinedFields(List.of(base.getFields().get(0), field("CustomFlag")));
        assertEquals(List.of("Code", "CustomFlag"), names(extended.getFields()));
        DbViewTableDef result = (DbViewTableDef)composed(base, extended).getSingleElement(name).getEObjectOrProxy();
        assertEquals(List.of("Code", "CustomFlag"), names(result.getFields()));
        assertSame(base.eClass(), result.eClass());
        assertSame(base.getMdObject(), result.getMdObject());
        assertFalse(result instanceof com._1c.g5.v8.dt.metadata.dbview.ExtendedDbViewTableDef);
    }

    @Test
    public void mergesTabularSectionFieldsRecursivelyAcrossSeveralExtensions() throws Exception
    {
        DbViewTableDef base = table("base");
        DbViewTableDef first = table("first");
        DbViewTableDef second = table("second");
        base.getFields().add(section("Items", "Quantity"));
        first.getFields().add(section("Items", "ExtensionOne"));
        second.getFields().add(section("Items", "ExtensionTwo"));
        DbViewTableDef result = (DbViewTableDef)composed(base, first, second).getSingleElement(name).getEObjectOrProxy();
        assertEquals(1, result.getFields().size());
        DbViewFieldTableDef items = (DbViewFieldTableDef)result.getFields().get(0);
        assertEquals(List.of("Quantity", "ExtensionOne", "ExtensionTwo"), names(items.getFields()));
        assertSame(base.getFields().get(0).getMdObject(), items.getMdObject());
        assertEquals(List.of("Quantity"), names(((DbViewFieldTableDef)base.getFields().get(0)).getFields()));
    }

    @Test
    public void uniqueLinkedTableAndUnextendedBaseTableStayUnchanged() throws Exception
    {
        DbViewTableDef base = table("base");
        DbViewTableDef linked = table("extension");
        linked.setName("Catalog.NewCatalog");
        IScope scope = composed(base, linked);
        assertSame(base, scope.getSingleElement(name).getEObjectOrProxy());
        assertSame(linked, scope.getSingleElement(QualifiedName.create("Catalog", "NewCatalog")).getEObjectOrProxy());
        assertEquals(2, size(scope.getAllElements()));
    }

    private IScope composed(DbViewTableDef base, DbViewTableDef... linked) throws Exception
    {
        List<IScope> scopes = new ArrayList<>();
        for (DbViewTableDef table : linked)
            scopes.add(new SimpleScope(List.of(EObjectDescription.create(
                QualifiedName.create(table.getName().split("\\.")), table))));
        return new ContextLinksExternalQueryScope(new SimpleScope(List.of(
            EObjectDescription.create(name, base, Map.of("origin", "base")))), scopes);
    }

    private DbViewTableDef table(String project)
    {
        var table = factory.createDbViewTableDef();
        table.setName("Catalog.Contracts");
        table.setMdObject(MdClassFactory.eINSTANCE.createCatalog());
        new ResourceImpl(URI.createURI("memory:/" + project)).getContents().add(table);
        return table;
    }

    private DbViewFieldDef field(String name)
    {
        var field = factory.createDbViewFieldFieldDef();
        field.setName(name);
        return field;
    }

    private DbViewFieldTableDef section(String name, String fieldName)
    {
        var section = factory.createDbViewFieldTableDef();
        section.setName(name);
        section.getFields().add(field(fieldName));
        return section;
    }

    private static List<String> names(List<?> fields)
    {
        return fields.stream().map(f -> ((DbViewFieldDef)f).getName()).toList();
    }

    private static int size(Iterable<?> values)
    {
        int count = 0;
        for (Object value : values) count++;
        return count;
    }
}
