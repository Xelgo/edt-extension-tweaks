# Extension Context Linking Investigation

This note replaces the early mixed `DEBUG_LOG.md` sections about adding BSL context from one EDT project into another.
The old raw log was intentionally removed from the working tree; use git history before the split if exact old snippets are needed.

## Goal

Make BSL code in one EDT project see metadata and exported common-module methods from selected linked extension projects.

Main user-facing scenario:

- An external data processor/report should see selected extension context.
- One extension should be able to use context from another selected extension.
- A donor extension must not be modified, warmed, or rebuilt just because another project consumes its context.
- Base configuration and extension-owned metadata must remain distinguishable enough to avoid invalid EDT adoption/update flows.

## Final Known State

- The release architecture requires the `ContextLinksCachedScopeProvider` layer for BSL context enrichment.
- `IContainer.Manager` and generic `IScopeProvider` hooks are not enough by themselves to make common-module methods visible.
- Query Constructor context is handled through a separate QL/BM path, documented in `DEBUG_QUERY_CONSTRUCTOR_CONTEXT.md`.
- The build-freeze root cause was later narrowed to missing Guice singleton lifecycle annotations, documented in `DEBUG_BUILD_FREEZE_SINGLETON.md`.

## Core Architecture

The BSL context path involved these components:

- `ContextLinks`: stores per-project linked context project names.
- `ContextLinksContainerManager`: extends visible Xtext containers so linked projects can be seen by the consumer project.
- `ContextLinksBslScopeProvider`: observes/extends BSL scope resolution.
- `ContextLinksCachedScopeProvider`: composes the consumer project's BSL cached scopes with selected linked project scopes.
- `ContextLinksModuleContextDefService`: mirrors EDT module context construction and was used to inspect/export common-module methods.

In 1C terms:

- The consumer project is the module where the user writes code.
- Linked projects are the extensions/configurations whose metadata and common-module exports should be visible.
- Scope is the EDT/Xtext equivalent of "what names, methods, properties, and metadata are visible at this code point".

## Timeline

### Common Module Scope Race

Initial symptom:

- Extension A could see Extension B.
- Extension B could not reliably see Extension A.
- Sometimes the visible direction changed after edits or background model rebuilds.

Important finding:

- Container links were symmetric.
- The actual common-module method scopes were not always ready when the first composite scope was built.
- A linked project scope could be `null` during the first request and then become available later, but the consumer scope had already been composed without it.

Rejected/partial attempts:

- Mirroring module scopes across provider instances.
- Forcing dependent extension refreshes.
- Touching dependent resources.
- Startup warm-up passes.

Lesson:

- Building a frozen composite scope too early is fragile. Linked scopes must either be lazily resolved or built only after EDT's own model is ready.

### Sliced Scope Contract

One live-scope attempt fixed timing but broke EDT's expected type contract:

```text
ContextLinksProjectScope cannot be cast to com._1c.g5.modeling.xtext.scoping.ISlicedScope
```

Lesson:

- EDT's BSL/Xtext pipeline expects some scopes to implement `ISlicedScope`, not just plain Xtext `IScope`.
- Any wrapper scope must preserve the interfaces implemented by the scope it wraps.

### Fresh Common Modules And Export Methods

Freshly created common modules could appear by name while their exported methods were still missing.

Observed pattern:

- The module name was visible.
- `ContextDef` sometimes had `allMethods=0`.
- Editing the donor extension module manually could make methods appear because EDT rebuilt the donor model.

Rejected/partial attempts:

- Fallback method extraction from BSL resources.
- Donor extension warm-up.
- Forcing full project rebuilds.

Lesson:

- Donor extension scope/model must not be warmed by the consumer. Touching or rebuilding donor projects risks duplicate methods and build overload.

### Manual Activation Direction

A later architecture direction made the plugin inactive by default and introduced manual activation.

Goal:

- Avoid any participation in normal full builds.
- Allow the user to activate context enrichment in the active editor/project.
- Reconcile only the active consumer editor, not donor extensions.

Important finding:

- Reconcile/touch of the consumer editor alone did not always route EDT through the module context path.
- The cached-scope layer still mattered for exported common-module methods.

## Extension Identity And Infobase Update Notes

During related application-update work, a separate extension identity issue was found:

- Two workspace extension projects had the same runtime extension UUID and name in `src/Configuration/Configuration.mdo`.
- EDT could then compare objects from one project as if they belonged to another.
- False "new object" / "modified extension" prompts can appear after synchronization if two projects represent the same runtime extension identity.

Lesson:

- Before blaming context links or synchronization code, check extension identity UUID/name uniqueness across workspace projects.

## Rules For Future Work

- Do not rebuild or touch donor projects as a side effect of a consumer asking for context.
- Keep base configuration, current extension, and foreign extension ownership explicit in logs and code paths.
- Preserve EDT/Xtext scope interfaces when wrapping native scopes.
- Treat "module name visible but method red" as a context-model problem, not merely a container-visibility problem.
- When replacing EDT services, compare the original class annotations and lifecycle first; see `DEBUG_BUILD_FREEZE_SINGLETON.md`.
