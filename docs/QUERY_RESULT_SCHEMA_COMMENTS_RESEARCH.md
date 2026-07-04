# Query Result Schema Comments Research

Date: 2026-07-04

This file records the research for adding query-result schema comments to EDT Extension Tweaks.

## Problem Statement

We have BSL code with a query text. Executing the query can return a value table or a selection, but the final result column names and types are not known to BSL completion. We need a way to generate and refresh a comment that describes the result tables, so later completion can use those names and types.

Important constraints:

- Query text can be a package, so there may be several statements and several result-producing tables.
- Some statements create temporary tables; some statements return final results.
- Type detection can be partial or fail.
- The best source of truth should be EDT Query Wizard / Query Constructor because it already understands metadata, aliases, temporary tables, linked extension context, and many expression types.

## Current Repository Baseline

The repository already has two separate context paths:

- BSL context path: `ContextLinksBslRuntimeModule`, `ContextLinksCachedScopeProvider`, `ContextLinksContainerManager`, `ContextLinksModuleContextDefService`.
- Query Constructor path: `ContextLinksV8GlobalScopeProviderProxy`, `ContextLinksQueryWizardWeavingServiceFactory`, `ContextLinksQueryWizardPatches`.

The Query Constructor path is explicitly separate from BSL scope. `DEBUG_QUERY_CONSTRUCTOR_CONTEXT.md` says BSL hooks alone do not cover QL/DCS resources.

Existing Query Wizard hooks:

- `SourcesEditProvider.equalsDbViewFromQueryNames(...)` is patched to avoid duplicate tables for linked/base DbView variants.
- `QueryWizardAdoptSupport.collectObjectsToAdopt()` is patched to skip foreign extension-owned objects.
- `QlEditor.setEditableExpression(... QueryWizardSource)` is patched to remember parent temporary tables for nested constructors.
- `QueryWizardControl.setQueryText(String)` is patched to apply pending nested temp tables.

Existing nested temp-table code already reflects into `QueryWizardSource.getAvailableTables()`, `AvailableTable.getType()`, and `AvailableTable.getDbViews(QueryWizardSource)`.

There is no existing comment format or parser for query result schema comments.

## Research Log

Initial repository check:

```powershell
git status --short --branch
rg --files
```

Finding:

- The first workspace folder was `sentry-jira-incident-bridge`, not this repository.
- The correct repository is `C:\Users\Xelgo\Documents\edt-extension-tweaks`.
- Working tree was clean before adding this research file.

Code and docs inspected:

```powershell
Get-Content -Encoding UTF8 DEBUG_QUERY_CONSTRUCTOR_CONTEXT.md
Get-Content -Encoding UTF8 DEBUG_EXTENSION_CONTEXT_LINKING.md
Get-Content -Encoding UTF8 DEBUG_BUILD_FREEZE_SINGLETON.md
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/plugin.xml
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/META-INF/MANIFEST.MF
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/src/ru/xelgo/edt/contextlinks/core/ContextLinksQueryWizardPatches.java
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/src/ru/xelgo/edt/contextlinks/core/ContextLinksQueryWizardWeavingServiceFactory.java
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/src/ru/xelgo/edt/contextlinks/core/ContextLinksV8GlobalScopeProviderProxy.java
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/src/ru/xelgo/edt/contextlinks/core/ContextLinksCachedScopeProvider.java
Get-Content -Encoding UTF8 bundles/ru.xelgo.edt.contextlinks.ui/src/ru/xelgo/edt/contextlinks/core/ContextLinksModuleContextDefService.java
```

EDT jars inspected:

```powershell
Get-ChildItem -Path C:\Users\Xelgo -Recurse -Filter '*qw.ui*.jar'
jar tf C:\Users\Xelgo\.p2\pool\plugins\com._1c.g5.v8.dt.qw.ui_9.2.100.v202605050943.jar
jar tf C:\Users\Xelgo\.p2\pool\plugins\com._1c.g5.v8.dt.qw.ui_9.2.101.v202605141817.jar
javap -classpath <qw;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.qw.ui.utils.QuerySchemaBuilder
javap -classpath <qw;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.qw.ui.utils.QueryWizardSource
javap -classpath <qw;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.qw.ui.utils.QueryTextResultProcessing
javap -classpath <ql.model;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.ql.model.QuerySchema
javap -classpath <ql.model;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery
javap -classpath <ql.model;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.ql.model.QuerySchemaOperator
javap -classpath <ql.model;ql;metadata;mcore jars> -public com._1c.g5.v8.dt.ql.model.QuerySchemaExpression
```

Finding:

- QW UI jars are in `C:\Users\Xelgo\.p2\pool\plugins`, not in the Tycho Maven cache.
- Both currently relevant QW UI jars expose the same important public signatures:
  - `com._1c.g5.v8.dt.qw.ui_9.2.100.v202605050943.jar`
  - `com._1c.g5.v8.dt.qw.ui_9.2.101.v202605141817.jar`

Confirmed Query Wizard / QL API:

- `QuerySchemaBuilder(IProject, boolean)`
- `QuerySchemaBuilder.buildQuerySchema(String)`
- `QuerySchemaBuilder.buildQuerySchema(String, boolean, IProject)`
- `QuerySchemaBuilder.getAvailableTables()`
- `QueryWizardSource(QuerySchema, IProject, IV8ProjectManager, IBmModelManager)`
- `QueryWizardSource.getQueries()`
- `QueryWizardSource.getCurrentOperator()`
- `QueryWizardSource.getTypeChecker()`
- `QueryWizardSource.getDerivedTypeDbViewComputer()`
- `QueryWizardSource.getAvailableTables()`
- `QueryWizardSource.getTempTableFields(QuerySchemaSource)`
- `QueryTextResultProcessing.getAllPresentations(QueryWizardSource, List<QuerySchemaExpression>)`
- `QueryTextResultProcessing.ExpressionPresentation.fieldDbView`
- `QuerySchema.getQueries()`
- `QuerySchemaSelectQuery.getOperators()`
- `QuerySchemaSelectQuery.getPlacementTable()`
- `QuerySchemaOperator.getPlacementTable()`
- `QuerySchemaOperator.getAddTempTable()`
- `QuerySchemaOperator.getSelectFields()`
- `QuerySchemaExpression.getAlias()`
- `QuerySchemaExpression.getExpression()`
- `QuerySchemaExpression.getType(IExpressionTypeChecker)`

Confirmed type-related API:

- `IExpressionTypeChecker.checkType(EObject)`
- `IExpressionTypeChecker.CheckResult.expressionTypeResult`
- `TypeDescriptionSource.toTypeDescription()`
- `TypeDescription.getTypes()`
- `TypeItem.getName()`
- `TypeItem.getNameRu()`
- `DbViewFieldDef.getType()`
- `DerivedTypeComputerForDbViewQuery.getFieldType(DbViewElement)`

BSL type system extension points inspected:

```powershell
jar tf com._1c.g5.v8.dt.bsl_28.0.1.v202605050943.jar
jar xf com._1c.g5.v8.dt.bsl_28.0.1.v202605050943.jar schema/dynamicContextDefProvider.exsd schema/invocationTypesComputerExtension.exsd
javap -classpath <bsl;bsl.model;mcore jars> -public com._1c.g5.v8.dt.bsl.typesystem.IDynamicContextDefProvider
javap -classpath <bsl;bsl.model;mcore jars> -public com._1c.g5.v8.dt.bsl.typesystem.IInvocationTypesComputerExtension
javap -classpath <bsl;bsl.model;mcore jars> -public com._1c.g5.v8.dt.bsl.typesystem.QueryUnloadInvocationTypesComputer
javap -classpath <bsl;bsl.model;mcore jars> -public com._1c.g5.v8.dt.bsl.typesystem.ValueTableDynamicContextDefProvider
```

Finding:

- EDT BSL has extension points:
  - `com._1c.g5.v8.dt.bsl.dynamicContextDefProvider`
  - `com._1c.g5.v8.dt.bsl.invocationTypesComputerExtension`
- Native EDT already registers `ValueTableDynamicContextDefProvider`, `StructureDynamicContextDefProvider`, and `QueryUnloadInvocationTypesComputer`.
- The public contracts are small:
  - `IDynamicContextDefProvider.hasDynamicContext(EObject, Type, Environments)`
  - `IDynamicContextDefProvider.computeDynamicType(EObject, Type, Environments)`
  - `IInvocationTypesComputerExtension.getTypes(Invocation)`

This is the likely integration point for making comments affect completion later.

## Chosen Stage-1 Comment Format

Use an idempotent BSL line-comment block. It must be safe for the BSL parser, easy to replace later, and honest about partial results.

Implemented MVP format:

```bsl
#Область edt_tweaks_query_result_1

    РезультатЗапроса = Запрос.Выполнить().Выгрузить();
    // РезультатЗапроса
    // * Item - CatalogRef.Items -
    // * Quantity - Number -
    // * Amount -

#КонецОбласти
```

Notes:

- `edt_tweaks_query_result_*` marks a service region. BSL code inside it can be replaced by refresh commands later.
- The comment line `// <variable>` starts the schema for that variable or result expression.
- Column lines use `// * <column> - <type> -`.
- `<type>` is omitted when it cannot be determined reliably.
- For a single final select, the generated executable line is `РезультатЗапроса = Запрос.Выполнить().Выгрузить();`.
- For several final results, the generated executable line is `РезультатыЗапроса = Запрос.ВыполнитьПакет();`, followed by schema comments for `РезультатыЗапроса[0]`, `РезультатыЗапроса[1]`, etc.
- One final select query produces one variable/result schema. Union branches are not emitted as separate result tables in the MVP.
- Temporary-table producers are skipped.

For Russian identifiers, store them as UTF-8 text in the comment. If escaping becomes necessary later, add a `nameEncoded=` field instead of replacing the human-readable `name=`.

## BSL Completion Contract

Regions named `edt_tweaks_query_result_*` are service regions for EDT Extension Tweaks. They do not change the declared/native BSL type of the variable.

Completion should treat the region as an additive dynamic-context hint:

- Resolve the native BSL type first, exactly as EDT does today.
- If the native type is compatible with query output (`ValueTable`, query selection row, package result item, or another supported dynamic context), read the nearest matching schema comment from the service region.
- Add the listed columns as dynamic properties for completion/navigation.
- Do not replace the variable type with the comment type.
- Ignore columns without a type for type-specific operations, but keep their names for completion.
- Ignore the service region if the surrounding code no longer matches a supported query execution pattern.

This keeps ordinary BSL semantics intact and uses the comment only as extra column knowledge.

## Recommended Architecture

### Stage 1: Generate Or Refresh The Comment

Create a new service, probably in `core`, for headless query schema extraction.

Input:

- query text;
- current `IProject`;
- optional BSL/Xtext context for better target-variable detection;
- optional existing comment block.

Flow:

1. Ensure current project is known with the same project lookup rules used by Query Constructor.
2. Build QL model with `QuerySchemaBuilder(project, false).buildQuerySchema(queryText)`.
3. Create `QueryWizardSource(schema, project, IV8ProjectManager, IBmModelManager)` using `ServiceAccess`.
4. Set available tables from `QuerySchemaBuilder.getAvailableTables()` if needed.
5. Iterate `QuerySchema.getQueries()`.
6. For every `QuerySchemaSelectQuery`, inspect `getOperators()`.
7. Treat operators with `getPlacementTable()` or `getAddTempTable()` as temporary-table producers, not final results.
8. Treat a select query without placement/add-temp as one final result; use the first result-producing operator as the result shape.
9. For every `QuerySchemaExpression` in `getSelectFields()`:
   - column name: `getAlias()` first, then presentation/expression fallback;
   - type: `getType(source.getTypeChecker())`;
   - fallback: `QueryTextResultProcessing.ExpressionPresentation.fieldDbView`;
   - fallback: `DbViewFieldDef.getType()`;
   - fallback: `source.getDerivedTypeDbViewComputer().getFieldType(fieldDbView)`;
   - otherwise omit `type`.
10. Future enhancement: merge union branches by column index. If names or types disagree, preserve the most stable name and omit or mark conflicting type.
11. MVP command copies the generated block to clipboard. Future command can replace an existing block with the same marker or insert a new block near the query text / target variable.

This stage should not require additional ASM weaving.

### Stage 2: Make BSL Completion Use The Comment

Do not inject local query columns into global BSL scopes. Use EDT BSL type/dynamic-context mechanisms.

Likely path:

1. Add a parser for `@edt-tweaks-query-schema` comment blocks.
2. Index blocks per BSL resource and query hash with a lightweight cache.
3. Register a custom `invocationTypesComputerExtension` for relevant calls if needed:
   - query `Unload` / `Выгрузить`;
   - package execution result access;
   - possibly query result `Select` / `Выбрать` paths.
4. Register a custom `dynamicContextDefProvider` for derived `ValueTable`, `ValueTableRow`, and selection row types produced from those calls.
5. Build dynamic `Type` / `ContextDef` objects with `McoreFactory`, adding `DerivedProperty` for columns.
6. Reuse native EDT behavior where possible and add columns only when the comment block can be matched to the invocation.

This stage is where comments become completion data.

### Stage 3: Commands And UI

Add user-facing commands only after the extraction service is stable:

- Generate query schema comment.
- Refresh query schema comment.
- Remove query schema comment.

The first implemented command is available in the embedded QL editor popup, next to the Query Wizard commands. It does not open Query Wizard and only copies the generated comment to clipboard.

Future commands can work from a BSL editor selection or caret position and insert/refresh the block directly in source code.

## Handling Query Packages

Classification draft:

- `SELECT ... INTO/PLACE temp`: temporary table producer, used as context for later queries.
- `ADD TO temp`: temporary table mutator, not a final result.
- `DROP temp`: no result.
- plain `SELECT`: final result.
- unioned `SELECT`: one final result. MVP uses the first result-producing operator as the shape; future versions can merge branches by index.
- unknown/unsupported query object: log and emit no final table or emit `kind=unknown`.

If a package has several final selects, emit several `@result` sections.

## Type Confidence Rules

Use conservative typing:

- `exact`: direct `QuerySchemaExpression.getType()` returns a non-empty type.
- `inferred`: type came from DbView field or derived DbView computer.
- `merged`: union/package branch merge kept compatible types.
- unknown/no reliable type: omit `type` in the generated comment.
- `conflict`: several incompatible types were detected.

Completion should prefer exact/inferred/merged and either hide or visually mark unknown/conflict fields.

## Risks

- QW UI classes are internal. Direct imports of `com._1c.g5.v8.dt.qw.ui` increase version coupling.
- Current plugin avoids direct QW imports by using `Eclipse-SupplementBundle` and reflection/ASM. A typed headless analyzer may require `Require-Bundle` or a reflection adapter.
- Weaving must still be registered before QW UI classes load for existing patches.
- Type inference is not guaranteed for parameters, arbitrary expressions, virtual tables, unions, and `SELECT *`.
- Query text can be stale relative to the generated comment; `queryHash` is mandatory.
- BSL completion must not make global scope noisy with local query columns.
- Do not rebuild or warm donor projects while extracting query schema.
- Preserve singleton/lifecycle contracts when adding new BSL service replacements or providers.

## Minimal-Loss Implementation Strategy

Recommended first implementation:

1. Add a reflection-based QW adapter for `QuerySchemaBuilder` and `QueryWizardSource`.
2. Add a command that generates a comment but does not affect completion yet.
3. Copy the generated comment to clipboard from the embedded QL editor popup.
4. Add debug logging for extraction fallback failures:
   - project;
   - query hash;
   - parsed query count;
   - result count;
   - temp-table count;
   - unknown columns/types;
   - fallback path used for each type.
5. Test on simple select, aliases, expressions, temp tables, package with several selects, union, and broken query.
6. Only after the comment is stable, add direct source refresh and BSL type-system integration through extension points.

This avoids changing completion and Query Wizard weaving in the first pass.

## Open Questions

- How should the command locate the query text reliably: selected string, BSL string literal under caret, or full `Query.Text` assignment chain?
- Should comments be attached to the query text or to the variable receiving `Execute().Unload()` / selection?
- How should we represent package execution results when BSL accesses them through result indexes?
- Should stale comments be ignored silently or shown as degraded completion with a warning marker?
- Should direct QW imports be accepted, or should the first version use reflection to keep manifest coupling lower?

## Current Conclusion

The idea is viable, but should be split into two products:

1. Query-schema comment generation and refresh, based on headless Query Wizard / QL model APIs.
2. BSL completion enrichment from those comments, based on EDT BSL type-system extension points.

The safest first step is to build and verify the generator. It gives value immediately as documentation near query code and produces stable test data for the later completion layer.

## Implemented MVP Notes

Implemented on 2026-07-04:

- `QueryResultSchemaCommentGenerator` builds a comment by reflecting into QW/QL internals.
- `GenerateQueryResultCommentHandler` resolves either the active embedded `QlEditor` or the active BSL editor query string, generates the comment, copies it to SWT clipboard, and shows `Комментарий скопирован в буфер обмена.`
- `plugin.xml` contributes the command to both `popup:xtext.embedded.editor` and `popup:#BslEditorContext`, next to the standard BSL Query Wizard commands.
- The copied snippet is a BSL service region named `edt_tweaks_query_result_1`.
- Unknown types are omitted from column comment lines.
- Temporary-table producers are skipped.
- One final select query produces one variable schema comment; query packages can produce several result schema comments inside the same service region.
- The implementation avoids direct QW imports and keeps the existing reflection/supplement-bundle style.

Verification:

```powershell
$env:JAVA_HOME = 'C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64'
& 'C:\Users\Xelgo\Documents\New project\.tools\apache-maven-3.9.9\bin\mvn.cmd' package -DskipTests
```

Result: `BUILD SUCCESS`; update site zip was produced at `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`.

## Implemented BSL Integration Notes

Implemented on 2026-07-04:

- `QueryResultServiceCommentIndex` parses BSL service regions named `edt_tweaks_query_result_*`.
- The parser understands current generated sections:
  - `// ResultVariable`
  - `// ResultArray[0]`
  - `// * ColumnName - TypeName -`
  - `// * ColumnName -`
- Parsed schemas are cached per Xtext resource by text hash and length. The cache is intentionally lightweight and does not scan other files.
- `ContextLinksTypesComputer` subclasses EDT `TypesComputer` and calls the native type computer first.
- The comment is applied only when EDT has already computed `ValueTable` or `ValueTableRow`.
- The integration clones the computed type before adding dynamic row properties, so standard EDT/global types are not mutated.
- For `ValueTable`, the cloned `collectionElementTypes` row type is enriched, and row-returning table methods such as `Add`, `Insert`, `Find`, `ChooseRow`, and `Get` are pointed to that enriched row type.
- For `QueryResultSelection` / `ВыборкаИзРезультатаЗапроса`, the computed selection type is cloned and enriched with the same column-name properties. This supports completion on `Selection.Column` after `Selection.Next()`.
- For package results, `ResultArray[0].Unload()` can match the comment section `// ResultArray[0]`.
- For `ValueTable.Columns` / `ТаблицаЗначений.Колонки`, the computed column collection type is cloned and enriched with column-name properties. These properties point to `ValueTableColumn` / `КолонкаТаблицыЗначений` when that type resolves.
- Column names are kept even when the type is unknown. Type containers are left empty for those columns.
- Known type names are resolved through the BSL `Expression.types` scope first, then through `BslUtil.getTypeByName(...)` and the EDT runtime-version object provider. This is required for metadata types such as `СправочникСсылка.<Name>`.
- The BSL runtime module now binds `TypesComputer` to `ContextLinksTypesComputer`.

Verification:

```powershell
$env:JAVA_HOME = 'C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64'
& 'C:\Users\Xelgo\Documents\New project\.tools\apache-maven-3.9.9\bin\mvn.cmd' package -DskipTests

powershell.exe -NoProfile -ExecutionPolicy Bypass -File '.\tools\redeploy-edt-main.ps1' `
  -Workspace 'C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\EDTTEST' `
  -EdtHome 'C:\Users\Xelgo\AppData\Local\1C\1cedtstart\installations\1C_EDT (Lite) 2026.1\1cedt' `
  -SkipBuild -NoRestart
```

Result:

- Maven build: `BUILD SUCCESS`.
- Installed bundle: `ru.xelgo.edt.contextlinks.ui_1.1.14.v202607041922.jar`.
- `bundles.info` points to `1.1.14.v202607041922`.
- EDTTEST was restarted with `-clean`.
- Workspace log check found no `BundleException`, `NoClassDefFoundError`, or `ClassNotFoundException` for the plugin.

Manual test target:

```bsl
#Область edt_tweaks_query_result_1

    РезультатЗапроса = Запрос.Выполнить().Выгрузить();
    // РезультатЗапроса
    // * Рест1 - СправочникСсылка.Расш1_Справочник -
    // * Ссылка - Число -

#КонецОбласти
```

Expected behavior: if EDT already sees `РезультатЗапроса` as `ТаблицаЗначений`, completion for rows derived from this table should include `Рест1` and `Ссылка`; `Рест1` and `Ссылка` should carry exact types when those type names resolve.

After the `ValueTable.Columns` follow-up, completion after `РезультатЗапроса.Колонки.` should also include `Ссылка` as a column object.

Follow-up changes:

- Package generation preserves real `ВыполнитьПакет()` indexes. Temporary-table producers still do not receive comments, but they still consume their array slot; final queries after a temp-table producer use `[1]`, `[2]`, etc.
- Package generation now creates named package variables: `Пакет1`, `Пакет2`, and comments bind to those variables.
- A preference `Помощник контекста результата запроса` controls generated model:
  - `Выгрузить() - ТаблицаЗначений` (default);
  - `Выбрать() - выборка`.
## Release Verification 1.1.15

Verified on 2026-07-04:

- Version was raised from `1.1.14` to `1.1.15`.
- Clean Maven build completed with `BUILD SUCCESS`.
- Built OSGi qualifier: `1.1.15.v202607041940`.
- Update-site archive: `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`.
- The release bundle was installed into `C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main` using EDT 2025.2.
- `bundles.info` contains a single active `ru.xelgo.edt.contextlinks.ui` entry for `1.1.15.v202607041940`.
- Workspace log check found no `BundleException`, `NoClassDefFoundError`, `ClassNotFoundException`, Guice errors, or plugin resolution errors for `ru.xelgo.edt.contextlinks.ui`.
