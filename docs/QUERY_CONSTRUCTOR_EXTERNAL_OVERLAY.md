# External processor query context: EDT 2026.2.2

## Observed failure

`CurrentFirstLinkedScope` keeps the first description for each qualified name. A catalog present in
both the base configuration and an extension therefore keeps only the base DB view. This drops the
extension's added attributes and tabular-section columns before the Query Wizard builds its tree.

## Relevant EDT checks

Inspection of the installed 2026.2.2 classes shows:

- `QuerySchemaBuilder` resolves scope descriptions, accepts `DbViewDef` objects, and groups them by
  the exact `getMdObject().eClass()` identity.
- `AvailableTablesContentProvider` reads table and tabular-section children via `getFields()`.
- `TablesAndFieldsTab` also has a filter for extended fields in extension projects.
- `QueryWizardServiceUtils.isObjectBelongsToCurrentProject` uses the project of `getMdObject()`.

Changing arbitrary metadata flags is unnecessary for the demonstrated loss of same-named DB views.
For external projects the base DB view can instead act as the identity and property source while
its visible field list incorporates selected extensions.

## External query scope

`ContextLinksExternalQueryScope` composes same-named tables only for external processors/reports:

| Property | Source |
| --- | --- |
| Qualified name, description user data, description URI | Base description |
| `getMdObject`, `eClass`, resource, containment, visibility, table properties | Base DB view |
| Field names, localized names, types, metadata references | Original field objects |
| Field list | Base plus selected extensions, deduplicated by name |
| Tabular-section columns | Recursively combined |

The normal getter and reflective `eGet(fields)` return the same list. Original metadata and DB views
are not modified. Extension-only tables remain available. Existing behavior for sibling-extension
projects is retained.

## Validation

Before the change, regressions reproduced loss of an added attribute and added tabular-section columns.
After the change tests check base identity and flags, preservation of added field types, multiple
extensions, real EDT `ExtendedDbViewTableDef`, consistent scope lookups and enumeration, and the
actual EDT `AvailableTablesContentProvider` output. The full Maven p2 build also passes.

The user confirmed that extension metadata appears in the installed EDT 2026.2.2 Query Wizard.
The release contains no temporary diagnostic-file writer, metadata dumps, or experimental QL weaving hook.
