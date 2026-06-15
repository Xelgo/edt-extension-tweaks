# Query Constructor Context Investigation

This note replaces the mixed `DEBUG_LOG.md` sections about EDT Query Constructor / QL context enrichment.

## Goal

Make the EDT Query Constructor see metadata from selected linked extension projects the same way BSL code sees linked context.

Important scenarios:

- Query Constructor opened from an extension module should show objects from selected sibling extensions.
- Query Constructor opened from an external data processor/report should show objects from selected extensions.
- Objects from another extension may remain visually "foreign" or grey, but the Query Constructor must not try to adopt them into the current extension as if they were base configuration objects.
- Selecting fields from linked metadata must not duplicate the same table repeatedly.

## Core Architecture

The Query Constructor path is not the same as the BSL editor path.

Key components:

- `ContextLinksV8GlobalScopeProviderRegistrar`
- `ContextLinksV8GlobalScopeProviderProxy`
- `ContextLinksQueryWizardWeavingServiceFactory`
- `ContextLinksQueryWizardPatches`

Important EDT concepts:

- Query Constructor uses QL/DCS resources, not normal BSL module resources.
- The active QL resource may not directly resolve to an `IProject`.
- The working fix used an `IResourceLookup` fallback to map `QlDcsResource` back to the current EDT project.
- BM global scope wrapping was more useful than plain DB-view scope injection.

## Timeline

### Research And First Failed Paths

Initial goal:

- Query Constructor table/field suggestions should include linked extension metadata.

Rejected/failed directions:

- Direct DB-view scope injection.
- Runtime extension points that were not on the live Query Constructor path.
- Assuming BSL container visibility automatically affects QL/DCS resources.

Lesson:

- Query Constructor requires a dedicated QL/BM path; BSL scope hooks alone do not cover it.

### BM Global Scope Wrapper

The useful path was a wrapper around EDT's non-API BM global scope provider.

Important finding:

- `QlDcsResource` could initially resolve `project=NULL`.
- After adding `IResourceLookup` fallback, the wrapper received the correct extension project for the Query Constructor resource.
- The wrapper could then compose the linked sibling extension scope and skip the base configuration when appropriate.

Expected log shape:

```text
QL BM global scope wrapper registered
QL BM provider call resource=...QlDcsResource project=<current extension> ql=true
QL BM scope project=<current extension> linked=[<linked extension>] skipped=[<base configuration>]
```

### Query Wizard Weaving

The Query Wizard UI had adoption logic that wanted to add selected objects into the current extension.

Problem:

- Foreign extension objects are not base configuration objects.
- If EDT prompts "Add used objects to extension?" for another extension's objects, confirming can fail or crash because the current extension cannot own them.

Fix direction:

- Weave/patch Query Wizard adoption filtering.
- Detect objects owned by foreign linked extensions.
- Skip those objects from the adoption candidate list.
- Keep legitimate base-configuration adoption candidates untouched.

Lesson:

- Grey/foreign objects in the UI are acceptable.
- The dangerous part is the later adoption step; ownership must be checked there.

### Duplicate Table Selection

Observed bug:

- Selecting multiple fields from the same linked catalog could add repeated tables:
  - `Расш1_Справочник`
  - `Расш1_Справочник1`
  - `Расш1_Справочник2`

Documents did not show the same problem in the tested scenario.

Fix direction:

- Use the base configuration metadata identifier for extended base objects.
- Do not treat the same base object as separate tables just because it is reached through base + extension overlays.

Lesson:

- Deduplication must use semantic metadata identity, not only display name or current owner project.

### Regression After BSL Split

During the build-freeze investigation, reducing plugin startup too aggressively broke Query Constructor context.

Root cause:

- Query Wizard weaving and QL BM global scope registration were accidentally removed from startup.

Restored registrations:

- `ContextLinksQueryWizardWeavingServiceFactory`
- `ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered()`
- `ContextLinksInfobaseSynchronizationManagerRegistrar.ensureRegistered()`

Result:

- User confirmed Query Constructor worked again after restoring the QW/QL registrations.
- This confirmed Query Constructor context is independent from some BSL runtime-module experiments.

## Verification Checklist

- Open Query Constructor from an extension or external data processor module.
- Confirm linked extension catalogs/documents appear.
- Select several fields from one linked catalog and confirm a single table is reused.
- Select foreign extension fields and press OK.
- Confirm EDT does not prompt to adopt foreign extension-owned objects into the current extension.
- Confirm base configuration objects still follow normal EDT adoption behavior.

## Rules For Future Work

- Keep Query Constructor registration independent from BSL build experiments.
- Do not remove QW weaving or QL BM scope wrapper while testing BSL build fixes.
- Always log object owner project when deciding whether adoption should be skipped.
- Treat base configuration adoption and foreign extension adoption as different flows.
