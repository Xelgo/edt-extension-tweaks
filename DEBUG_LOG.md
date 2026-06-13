# Debug Log

This file records the debugging trail for EDT context-link behavior. Keep it in git together with code changes so later commits explain not only what changed, but why we believed it was the right move.

Rules for this log:

- Add an entry for every meaningful EDT log investigation, failed theory, architecture turn, and successful build.
- Prefer short curated excerpts and line numbers from `.metadata/.log`; do not commit the raw workspace log.
- Link conclusions to commit hashes and update-site builds when possible.
- Record failed attempts as first-class information.

## 2026-06-13 - Common Module Scope Race

Workspace log inspected:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\file\.metadata\.log
```

Active projects in the scenario:

- `Configuration`
- `Configuration.Rest1`
- `Configuration.Rest2`

User-visible symptom:

- `Ext1` can see `Ext2`.
- `Ext2` cannot see `Ext1`.
- Sometimes the direction flips, as if projects compete during scope/model initialization.
- Metadata second-level objects such as documents, catalogs, exchange plans, and similar objects behave acceptably.
- Common modules are the unstable/problematic area.

Key findings from EDT logs:

1. Project settings are per-project and symmetric enough for the tested case.

```text
project=Configuration.Rest1 links=[Configuration, Configuration.Rest2]
project=Configuration.Rest2 links=[Configuration, Configuration.Rest1]
```

2. Xtext containers are also added symmetrically.

```text
containers.exit project=Configuration.Rest1 resultCount=3 added=[Configuration=Configuration, Configuration.Rest2=Configuration.Rest2] unresolved=[]
containers.exit project=Configuration.Rest2 resultCount=3 added=[Configuration=Configuration, Configuration.Rest1=Configuration.Rest1] unresolved=[]
```

This means the root failure is not simply "linked project missing from containers".

3. The earlier module-scope mirror attempt was mostly not on the failure path at first.

Before deeper diagnostics, logs showed repeated:

```text
cache.module.clear ... removed=0
```

but no useful `cache.module.add` or `cache.module.get-fallback` for the failing path. That made the previous `BslCachedScopeProvider.addScope/getScope` mirror insufficient as a real fix.

Related commit:

```text
012f052 Mirror module scopes across provider instances
```

4. `IBslModuleContextDefService` is on the common-module path.

`BslScopeProvider` calls `IBslModuleContextDefService.getContextDef(module)` when constructing context methods/properties. EDT's common module implementation is:

```text
com._1c.g5.v8.dt.internal.bsl.contextdef.BslModuleContextDefExtensionCommonModule
```

It obtains the common module owner and returns the `ContextDef` from the produced type behind the common module metadata object.

Diagnostic commits:

```text
b4f5995 Trace module context definitions
938a50b Trace common module BSL scopes
```

5. Diagnostics proved the asymmetry at runtime.

`Ext2_CommonModule` context:

```text
line 10784
[module.context.provider] project=Configuration.Rest2
moduleUri=platform:/resource/Configuration.Rest2/src/CommonModules/Ext2_CommonModule/Module.bsl
owner=Ext2_CommonModule
provider=com._1c.g5.v8.dt.internal.bsl.contextdef.BslModuleContextDefExtensionCommonModule
context=RefContextDelegatingContextDefImpl{properties=1,methods=0,allProperties=1,allMethods=1,refs=1}
methods=[Тест]
```

`Ext1_CommonModule` context:

```text
line 11540
[module.context.provider] project=Configuration.Rest1
moduleUri=platform:/resource/Configuration.Rest1/src/CommonModules/Ext1_CommonModule/Module.bsl
owner=Ext1_CommonModule
provider=com._1c.g5.v8.dt.internal.bsl.contextdef.BslModuleContextDefExtensionCommonModule
context=RefContextDelegatingContextDefImpl{properties=1,methods=0,allProperties=1,allMethods=2,refs=1}
methods=[Тест, Тест2]
```

Interpretation:

- `Rest2` has only its own common-module method in the context when first built.
- `Rest1` later sees more because the linked/project scopes are already warmer by then.
- This matches the user observation that one extension sees the other, while the other does not.

6. The actual BSL feature scope confirms the same direction.

For `Configuration.Rest2`:

```text
line 10928
[bsl.scope] project=Configuration.Rest2
context=FeatureEntry reference=feature
sample=[Doc, ThisObject, ЭтотОбъект, Ext2_CommonModule, Ext2_CommonModule, Ext2_CommonModule1, ...]
```

The sample includes `Ext2_*`, but does not include `Ext1_CommonModule`.

For `Configuration.Rest1`:

```text
line 11666
[bsl.scope] project=Configuration.Rest1
context=FeatureEntry reference=feature
sample=[test4, test2, test3, test, ext2, ThisObject, ЭтотОбъект, Ext1_CommonModule, ...]
```

`Rest1` sees items that appear to come from `Rest2`, while `Rest2` does not see `Ext1`. This supports the scope initialization race hypothesis.

7. Timing clue that led to the fix.

Around the failing `Rest2` build:

```text
09:52:13 Rest2 property scope is added first
09:52:13 Rest2 module context is built with allMethods=1
09:52:20 Rest1 property scope is added later
09:52:20 Rest1 module context is built with allMethods=2
```

Conclusion:

The plugin was composing a `CompositeScope` using only linked project scopes that existed at the exact first request. If a linked project scope was still `null`, the composite was permanently missing it until a later clear/rebuild. That explains the apparent competition between extensions.

Implemented fix:

```text
5268488 Use live linked project scopes
```

Change summary:

- Replace frozen `CompositeScope` composition with `ContextLinksProjectScope`.
- `ContextLinksProjectScope` keeps the own scope and linked project names.
- On every `getSingleElement`, `getElements`, and `getAllElements`, it resolves currently available linked scopes again.
- This avoids sleeps, retries, background rebuilds, and blocking EDT.

Build result:

```text
BUILD SUCCESS
Finished at: 2026-06-13T09:55:29+04:00
```

Update site:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
Length: 88047
LastWriteTime: 2026-06-13 09:55:29 +04:00
```

Remaining risks:

- The live scope can call `getAllElements()` across linked projects repeatedly. Watch EDT responsiveness after install.
- Diagnostic providers are still present and should be reduced once the behavior is stable.
- New common module creation still needs a focused retest: the fix addresses a scope initialization race, but may not fully solve derived context model refresh after brand-new objects are created.

Next useful log checks after installing `5268488`:

- Compare `[module.context.provider]` for `Ext1_CommonModule` and `Ext2_CommonModule`.
- Confirm `[bsl.scope] project=Configuration.Rest2 context=FeatureEntry reference=feature` includes `Ext1_CommonModule`.
- Confirm `[bsl.scope] project=Configuration.Rest1 context=FeatureEntry reference=feature` includes `Ext2_CommonModule`.
- Check whether repeated `cache.module.clear` after creating a new common module is followed by new `cache.module.add` and updated samples.

## 2026-06-13 - Live Scope Broke Sliced Scope Contract

Workspace log inspected:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\file\.metadata\.log
Length: 926193
LastWriteTime: 2026-06-13 09:59:29 +04:00
```

User-visible change after installing `5268488`:

- The previous missing-method diagnostics appear to be gone, so common-module context model construction improved.
- Content assist is now broken and new diagnostics appear.

Key EDT error:

```text
org.eclipse.xtext.ui.editor.model.XtextDocument - Error in IXtextModelListener
java.lang.ClassCastException:
ru.xelgo.edt.contextlinks.core.ContextLinksCachedScopeProvider$ContextLinksProjectScope
cannot be cast to com._1c.g5.modeling.xtext.scoping.ISlicedScope
```

Content-assist path:

```text
org.eclipse.xtext.ui.editor.contentassist.AbstractJavaBasedContentProposalProvider
Error in polymorphic dispatcher:
ContextLinksProjectScope cannot be cast to ISlicedScope
```

Important stack frame:

```text
com._1c.g5.v8.dt.bsl.bm.scoping.BmAwareBslGlobalScopeProvider.getDefaultGlobalScope(BmAwareBslGlobalScopeProvider.java:116)
```

Conclusion:

The live scope idea is still useful for avoiding the initialization race, but the wrapper returned by
`getPropertyScope()` and `getTypeItemScope()` must preserve EDT's `ISlicedScope` contract. The previous
implementation only implemented Xtext `IScope`, so EDT failed when BSL derived-state and content-assist code
cast the returned global scope to `ISlicedScope`.

Implemented fix in the next attempt:

- Make `ContextLinksProjectScope` implement `ISlicedScope`.
- Forward sliced calls to linked scopes when those scopes also implement `ISlicedScope`.
- Fall back to plain `IScope` methods for non-sliced scopes.

Build result:

```text
BUILD SUCCESS
Finished at: 2026-06-13T10:02:24+04:00
```

Update site:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
Length: 89290
LastWriteTime: 2026-06-13 10:02:24 +04:00
```

Expected next log check:

- `ClassCastException ... ContextLinksProjectScope cannot be cast to ... ISlicedScope` should disappear.
- Then re-check whether `Configuration.Rest2` content assist includes `Ext1_CommonModule`.

## 2026-06-13 - New Common Module Stays Visible Only In One Extension

Workspace log inspected:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\file\.metadata\.log
Length: 584755
LastWriteTime: 2026-06-13 10:06:34 +04:00
```

User-visible state after installing `13dc42c`:

- Existing cross-extension common-module context mostly works.
- Creating a fresh common module in one extension updates context for only one extension.
- The linked extension does not immediately see the new module until a broader rebuild/restart path.

Key log signals:

```text
[cache.clearTypeItems] project=Configuration.Rest1
[cache.clearProperties] project=Configuration.Rest1
[cache.addProperty] project=Configuration.Rest1 scope=... elements=3090
[cache.addTypeItem] project=Configuration.Rest1 scope=... elements=5287
[cache.addTypeItem] project=Configuration.Rest1 scope=... elements=10498
```

New modules appeared in BSL resource processing:

```text
platform:/resource/Configuration.Rest1/src/CommonModules/XER/Module.bsl
platform:/resource/Configuration.Rest1/src/CommonModules/Ext1_Test1/Module.bsl
```

But nearby logs mostly showed module-level clears:

```text
[cache.module.clear] module=platform:/resource/Configuration.Rest1/src/CommonModules/Ext1_Test1/Module.bsl#/0 removed=0
[cache.module.clear] module=platform:/resource/Configuration.Rest2/src/CommonModules/Ext2_CommonModule/Module.bsl#/0 removed=0
```

Conclusion:

The live linked project scope fixes stale project-scope composition, but already cached BSL module scopes can still
hold old global-scope results. When `Configuration.Rest1` rebuilds type/property project scopes after a new common
module is created, an already opened module from `Configuration.Rest2` can keep using a module-level scope cached
before `Rest1` changed.

Implemented fix in the next attempt:

- Track a monotonically increasing project-scope version for every type/property cache clear/add.
- Store the own+linked project-scope version fingerprint when a module scope is cached.
- If `getScope()` sees that a cached module scope was built against an older own or linked project version, treat it
  as stale and return `null` so EDT rebuilds it through its normal lazy path.

Build result:

```text
BUILD SUCCESS
Finished at: 2026-06-13T10:10:14+04:00
```

Update site:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
Length: 87849
LastWriteTime: 2026-06-13 10:10:14 +04:00
```

Expected next log check:

- After creating a new module, logs should show `[cache.project.version]` for the changed extension.
- When a linked extension asks for content assist, stale module scopes should produce `[cache.module.stale]`.
- The linked extension should then rebuild and include the freshly created common module.

## 2026-06-13 - Fresh Module Name Visible But Export Method Missing

Workspace log inspected:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\file\.metadata\.log
Length: 594301
LastWriteTime: 2026-06-13 10:13:42 +04:00
```

User-visible state after installing `39c527f`:

- A freshly created common module appears in content assist from both extensions.
- In one direction, invoking/opening the module works enough that content assist offers the module name.
- The linter still reports that the module does not exist, and the module's exported method is not visible.

Relevant files observed in the EDT workspace:

```text
Configuration.Rest1/src/CommonModules/Ext1_Test1/Module.bsl
Configuration.Rest2/src/CommonModules/Ext2_CommonModule1/Module.bsl
Configuration.Rest2/src/CommonModules/Ext2_test2/Module.bsl
```

Key log split:

```text
[module.context.provider] project=Configuration.Rest2
moduleUri=platform:/resource/Configuration.Rest2/src/CommonModules/Ext2_CommonModule1/Module.bsl
context=...{allMethods=1}
methods=[Тест]
```

but another fresh module was present with an empty method context:

```text
[module.context.provider] project=Configuration.Rest2
moduleUri=platform:/resource/Configuration.Rest2/src/CommonModules/Ext2_test2/Module.bsl
context=...{allMethods=0}
methods=[]
```

Conclusion:

The remaining failure is not simply project/module name visibility. EDT resolves common module methods through
`CommonModule.getContextDef() -> first property type -> type.getContextDef()`. A project-level property/type scope
can therefore expose the module name while still carrying an older or empty `ContextDef` for its exported methods.

The previous module-scope versioning attempt did not cover this fully because EDT repeatedly calls
`clearScopes(module)` for common module BSL resources without necessarily clearing the project-level property/type
scope that owns the common module `ContextDef`.

Implemented fix in the next attempt:

- When `clearScopes(module)` is called for a common module, also clear the owning project's property and type-item
  scopes.
- This forces the common module property/type context to be rebuilt after fresh module creation or export method
  changes.
- The existing project-version diagnostics should then make linked module scopes stale and rebuild them on demand.

Build result:

```text
BUILD SUCCESS
Finished at: 2026-06-13T10:17:38+04:00
```

Update site:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
Length: 90728
LastWriteTime: 2026-06-13 10:17:38 +04:00
```

Expected next log check:

- After editing/creating `Ext2_test2` or `Ext1_Test1`, logs should show `[cache.project.clear-for-module]`.
- Then `[cache.clearProperties]`, `[cache.clearTypeItems]`, and `[cache.project.version]` should follow for that
  project.
- The next `[module.context.provider]` for the fresh common module should report `allMethods > 0`.

Follow-up after installing `260c792`:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\file\.metadata\.log
Length: 87909
LastWriteTime: 2026-06-13 10:19:39 +04:00
```

The attempt broke the previously working linked-extension context. Logs showed that
`[cache.project.clear-for-module]` fired repeatedly for normal common-module clear events:

```text
[cache.project.clear-for-module] project=Configuration.Rest2 module=.../Ext2_test2/Module.bsl#/0
[cache.clearProperties] project=Configuration.Rest2
[cache.clearTypeItems] project=Configuration.Rest2
```

Immediately after that, EDT often requested scopes while the owning project scope was still empty:

```text
[cache.get.property] project=Configuration.Rest2{accessible=true} own=NULL
[cache.get.type-item] project=Configuration.Rest2{accessible=true} own=NULL
```

The resulting BSL type scope lost the context-link wrapper and became a plain own-project scope:

```text
[bsl.scope] project=Configuration.Rest2 ... reference=types
scope=com._1c.g5.modeling.xtext.scoping.NonShadowedSelectableBasedScope@...
```

Conclusion:

Clearing project-level property/type scopes from inside every common-module `clearScopes()` call is too aggressive.
It races EDT's own lazy rebuild and can temporarily remove the owning scope at exactly the moment BSL asks for it,
which prevents `ContextLinksProjectScope` from being returned and drops linked extension context.

Action:

- Revert the code part of `260c792`.
- Keep the diagnostics and the failed-attempt analysis in this log.
- Next attempt should avoid clearing global project scopes from module clear events; it needs a narrower fix for
  stale common-module `ContextDef`.

Recovery build:

```text
BUILD SUCCESS
Finished at: 2026-06-13T10:21:20+04:00
```

Update site:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
Length: 88011
LastWriteTime: 2026-06-13 10:21:19 +04:00
```

Verification after installing `5aa1d0a`:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\file\.metadata\.log
Length: 1008787
LastWriteTime: 2026-06-13 10:23:15 +04:00
```

The linked-extension context returned. Late log entries again show linked property/type scopes in both directions:

```text
[cache.linked.property] project=Configuration.Rest1 linked=Configuration.Rest2 scope=... elements=1556
[cache.linked.type-item] project=Configuration.Rest1 linked=Configuration.Rest2 scope=... elements=10504
[cache.linked.property] project=Configuration.Rest2 linked=Configuration.Rest1 scope=... elements=3100
[cache.linked.type-item] project=Configuration.Rest2 linked=Configuration.Rest1 scope=... elements=10498
```

BSL type scopes also returned to the live wrapper path:

```text
[bsl.scope] project=Configuration.Rest2 ... context=Module reference=retValType
scope=ru.xelgo.edt.contextlinks.core.ContextLinksCachedScopeProvider$ContextLinksProjectScope@...

[bsl.scope] project=Configuration.Rest2 ... context=Procedure reference=types
scope=ru.xelgo.edt.contextlinks.core.ContextLinksCachedScopeProvider$ContextLinksProjectScope@...
```

Residual problem remains:

```text
[module.context.provider] project=Configuration.Rest2
moduleUri=platform:/resource/Configuration.Rest2/src/CommonModules/Ext2_ОбщийМодуль3/Module.bsl
context=...{allMethods=0}
methods=[]
```

Conclusion:

`5aa1d0a` restores the cross-extension context. The remaining bug is narrower: a freshly created common module can
be visible as a module/type item while its exported method context is still empty in EDT's `CommonModule.getContextDef()`
path. Next attempts should target fresh common-module `ContextDef` refresh or delayed resolution without global
project-scope clearing.

## 2026-06-13 - Fallback ContextDef From Fresh BSL Export Methods

Working hypothesis:

- EDT exposes common module names through project type/property scopes.
- Exported methods after the dot come from `CommonModule.getContextDef()`.
- For fresh common modules, EDT can temporarily expose the module name while `CommonModule.getContextDef()` still has
  `allMethods=0`.
- Clearing global project scopes from `clearScopes()` is unsafe, so the next attempt must stay local to the module
  context definition path.

Implementation attempt:

- In `ContextLinksModuleContextDefService`, keep EDT's normal `ContextDef` when it already has methods.
- If a common module `ContextDef` is `null` or has `allMethods=0`, inspect the live BSL `Module.allMethods()`.
- If the BSL module contains exported methods, build a minimal transient `mcore.ContextDef` with matching method names
  and parameter counts.
- Return this fallback only for common modules and log it as `[module.context.fallback]`.

Expected next log check:

- For a fresh module such as `Ext2_ОбщийМодуль3`, `[module.context.provider]` may still show `allMethods=0`.
- Immediately after that, `[module.context.fallback]` should show `allMethods > 0` and `methods=[...]`.
- The linked extension should then see exported methods without losing `ContextLinksProjectScope`.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 10:27:27 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `92142` bytes.

## 2026-06-13 - Resource-Backed Fallback ContextDef Attempt

Latest user check:

- The problem remains after commit `26a8797`.
- The current `.metadata/.log` has no `[module.context.fallback]` lines in the visible active log segment, but it does
  show an EDT hover crash:
  `NullPointerException: Cannot invoke "org.eclipse.emf.ecore.resource.Resource.getURI()" because ... eResource() is null`.
- The stack is inside EDT documentation/hover code:
  `BslDocumentationProvider.getDocByNotFormalParamVariable(...)`.

Analysis:

- Returning a standalone `McoreFactory.createContextDef()` is unsafe because its methods and parameters are transient
  EMF objects without `eResource`.
- Completion may accept those objects, but EDT hover/documentation expects semantic objects to belong to a resource.

Implementation attempt:

- Do not return a standalone transient fallback context definition.
- If EDT returns an empty `ContextDef`, add fallback methods into that existing context definition.
- If EDT returns `null`, create a fallback `ContextDef` and attach it through `CommonModule.setContextDef(...)`.
- If the resulting `ContextDef` still has no `eResource`, skip fallback entirely.
- Extend context logging with `ContextDef.eResource()` so the next log proves whether fallback objects are resource-backed.

Expected next log check:

- `[module.context.fallback] ... context=...resource=platform:/resource/...` should appear.
- The previous hover NPE about `EObject.eResource().getURI()` should disappear.

Build:

- First build attempt failed at `2026-06-13 10:33:49 +04:00` because EDT/another process locked the previous update
  site zip and Maven could not delete it during `clean`.
- After closing EDT, `mvn package -DskipTests` completed successfully at `2026-06-13 10:35:04 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `92122` bytes.

## 2026-06-13 - Invalidate Linked Module Scopes On Module Scope Changes

Latest user check:

- The problem remains after commit `2da81f3`.
- Fresh log confirms the previous transient fallback suspicion is no longer the main path:
  `[module.context.provider]` for `Ext2_ОбщийМодуль4` shows `allMethods=1`, `methods=[Тест]`, and a BM resource-backed
  context definition.
- No `[module.context.fallback]` is needed for that fresh module.

Important log facts:

- Early after startup, `Configuration.Rest2` builds linked scopes while `Configuration.Rest1` is still unavailable:
  `linked=Configuration.Rest1 scope=NULL`.
- Later, `Configuration.Rest1` becomes available and gets property/type scopes.
- Even later, `Configuration.Rest1` module scopes are rebuilt, but project-scope versioning did not change on
  `addScope(...)` / `clearScopes(Module)`.

Analysis:

- Project property/type scope versioning is not enough for common-module method visibility.
- Common module exported methods can depend on BSL module context/method scopes.
- A module in one extension can keep a cached scope built before the linked extension rebuilt its module scopes.

Implementation attempt:

- Bump the project scope version when EDT adds a BSL module scope via `addScope(...)`.
- Bump the project scope version when EDT clears module scopes via `clearScopes(Module)`.
- Treat a non-null EDT module scope with no mirrored version as untracked/stale, log `[cache.module.untracked]`, and
  force rebuild instead of trusting a potentially stale super-cache entry.

Expected next log check:

- After linked project module scope rebuilds, dependent module scopes should log `[cache.module.stale]` or
  `[cache.module.untracked]` and be rebuilt.
- `Ext2` should stop holding method/type scopes from before `Ext1` finished its common-module scope rebuild.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 10:40:04 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `92364` bytes.

## 2026-06-13 - Startup Warm-Up And Extension-Only Configuration Command

Current workspace under investigation:

- `C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main`

User observation:

- On primary EDT startup, the background linter/model build starts too early.
- `Extension 2` does not see `Extension 1`.
- If the user opens a module in `Extension 1`, returns to the old module, and changes one line, EDT starts another
  validation/model build and the linked context becomes correct.

Analysis:

- The manual edit is acting as a delayed rebuild/warm-up after all linked extension projects have become available.
- We should emulate that post-startup pass for configured extension links instead of relying on the very first
  background model build.
- The configure command is meaningful only on extension projects. The base configuration context is already available
  in EDT, and external data processors/reports are not a target for this plugin.

Implementation attempt:

- Add `ContextLinksStartup` through `org.eclipse.ui.startup`.
- Schedule two system `WorkspaceJob` warm-up passes after startup (`15s` and `45s`).
- For each accessible extension project with configured links, full-build linked extension projects first, then the
  configured extension project itself.
- Add `ContextLinks.isExtensionProject(IProject)` using EDT `IV8ProjectManager` and `IExtensionProject`.
- Add a navigator property tester so the **Настроить контекст EDT** menu item is visible only for extension projects.
- Guard the handler as well, and filter dialog candidates to extension projects only.

Expected next log check:

- Startup should log `EDT Context Links startup warm-up scheduled`.
- Warm-up passes should log `startup warm-up pass=1/2 projects=[...]` and per-project build lines.
- After that, the initial diagnostics should match the state previously achieved only after manual module edit.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 16:54:41 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `95058` bytes.

## 2026-06-13 - Quiet Heavy Diagnostic Logging

Latest user check:

- Warm-up appears to have triggered at least partially, but the EDT Error Log became too noisy.
- The `.metadata/.log` file is expensive to inspect because the plugin writes thousands of diagnostic `INFO` entries from hot
  scope/cache paths.

Analysis:

- The most expensive log source is `ContextLinks.logDebug(...)`, especially cache version bumps, module stale/untracked checks,
  linked scope summaries, container visibility traces, and common-module scope samples.
- Some messages were also logged as `WARNING` even though they were lifecycle/debug markers, not problems.
- Keeping all this in the Error Log makes each next investigation slower and can hide real EDT errors.

Implementation attempt:

- Disable `ContextLinks.logDebug(...)` by default.
- Keep the debug channel available with JVM flag:
  `-Dru.xelgo.edt.contextlinks.ui.debug=true`.
- Guard the heaviest scope/sample logging so it does not enumerate scope elements while debug is disabled.
- Downgrade provider construction/binding and diagnostic scope/container summaries from `WARNING` to debug.
- Keep visible warnings for startup warm-up passes, failed settings reads, failed container handle resolution, and configuration save
  through the UI.

Expected next log check:

- Fresh `.metadata/.log` should no longer contain thousands of `EDT Context Links DEBUG [...]` `INFO` entries.
- The useful startup markers should remain:
  `EDT Context Links startup warm-up scheduled`,
  `EDT Context Links startup warm-up pass=...`,
  `EDT Context Links startup warm-up build pass=...`.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 17:08:58 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `95471` bytes.

## 2026-06-13 - Refresh Dependent Extensions On Linked Project Resource Changes

User observation:

- Adding an attribute to `Extension2` (`Document Расш2_Документ.Реквизит3`) starts an EDT background model rebuild.
- During that rebuild `Extension1` loses the linked context completely.
- A trivial manual edit inside an `Extension1` module restores the context.

Analysis:

- This looks like a missing reverse invalidation, not like a missing context setting.
- The current code mostly works when the active project asks for its configured links.
- When a linked project changes (`Extension2`), the dependent project (`Extension1`) is not forced to rebuild/revalidate early enough.
- The user's manual whitespace edit is effectively an incremental build/revalidation of the dependent project after the linked project changed.

Implementation attempt:

- Add `ContextLinksDependencyRefresh`, a workspace resource-change listener.
- Register it from both `ContextLinksStartup` and `ContextLinksBslRuntimeModule`; this gives us a fallback if the UI startup extension is late.
- Watch only content/resource changes under extension project `src` and `DT-INF` folders.
- Coalesce changed projects for `2500 ms`.
- For each changed extension project, run an incremental build for:
  - the changed extension project itself;
  - every accessible extension project whose individual context settings include that changed project.

Expected next log check:

- On changing `Extension2`, log should show:
  `EDT Context Links dependency refresh changed=[...] projects=[..., ...]`.
- The project list should include `Extension1` when `Extension1` has `Extension2` in its configured context links.
- `Extension1` should no longer require a manual whitespace edit to restore linked context after `Extension2` metadata changes.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 17:18:16 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `103640` bytes.

## 2026-06-13 - Touch Dependent Extension Resources Before Refresh Build

Latest user check:

- After commit `99e3a4d`, nothing changed.
- Fresh log proves the listener did run:
  `EDT Context Links dependency refresh listener installed source=bsl-runtime-module`.
- On extension changes, the listener queued both sides:
  `changed=[...Расширение2] ... dependentProjects/previous projects include [...Расширение]`.

Analysis:

- The failed attempt is useful: the problem is not that we missed the filesystem/resource event.
- `project.build(INCREMENTAL_BUILD)` for `Extension1` is not equivalent to the user's manual whitespace edit in an
  `Extension1` module.
- EDT likely needs an actual resource delta inside the dependent extension's `src` tree to invalidate/revalidate the BSL model.

Implementation attempt:

- Split dependency refresh into changed extension projects and dependent extension projects.
- Build the changed extension project first.
- Before building each dependent extension project, `touch(...)` one existing `.bsl`/`.mdo` resource under its `src` folder.
- Suppress our own synthetic touch deltas for `15s` so bidirectional links do not create an endless Ext1/Ext2 refresh loop.
- Log the touched resource so the next `.metadata/.log` proves whether EDT saw the synthetic equivalent of a manual edit.

Build:

- First `mvn package -DskipTests` attempt failed at `2026-06-13 17:22:41 +04:00`.
- Java compilation succeeded, but repository packaging failed because Maven could not delete the existing locked update-site zip:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`.
- Second retry attempt at `2026-06-13 17:23:14 +04:00` used an unquoted PowerShell `-Dmaven.clean.skip=true`
  argument and failed before build execution because PowerShell/Maven interpreted it as an invalid lifecycle phase.
- After the update-site zip was released, `mvn package -DskipTests` completed successfully at
  `2026-06-13 17:23:48 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `103436` bytes.

## 2026-06-13 - Narrow Synthetic Touch Suppression To Resource Path

Latest log check:

- The new build was installed and running.
- `dependency refresh touched ...` appeared, so the synthetic resource delta mechanism is active.
- The last observed refresh was triggered by `Extension1` and touched a resource in `Extension2`.

Analysis:

- Suppression in commit `6f43ce5` was too broad: after touching a dependent project, all deltas from that project were ignored for
  `15s`.
- If the user changed a real metadata file in `Extension2` shortly after our synthetic touch in `Extension2`, the listener could
  accidentally ignore the real user delta.

Implementation attempt:

- Replace project-wide synthetic delta suppression with exact resource-path suppression.
- Only the specific resource touched by the plugin is ignored during the suppression window.
- Other files in the same extension project, such as the document metadata changed by adding a requisite, should still trigger
  dependency refresh.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 17:27:32 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `105931` bytes.

## 2026-06-13 - Root Cause Hypothesis: Linked Scope Uses Live EDT Cache, Not Last Stable Snapshot

User question:

- When `Extension2` metadata changes, for example adding a document requisite, it is expected that `Extension2` context rebuilds.
- But why does `Extension1` lose the entire linked context instead of continuing to see the previous stable `Extension2` context?

Code observation:

- `ContextLinksProjectScope` stores:
  - `ownScope`;
  - linked project names;
  - scope kind.
- It does **not** store linked `IScope` objects or a stable snapshot.
- Every lookup calls `getLinkedScopes()`, which resolves current linked scopes through:
  - `provider.getDirectTypeItemScope(linkedProject)`;
  - `provider.getDirectPropertyScope(linkedProject)`.

Analysis:

- During EDT rebuild of `Extension2`, EDT likely clears its direct project scopes first and fills them later.
- While that rebuild gap exists, `getDirect...Scope(Extension2)` can return `null`.
- `ContextLinksProjectScope.getLinkedScopes()` silently skips `null` linked scopes.
- Therefore `Extension1` does not see the previous `Extension2` context; it sees no linked `Extension2` scope at all.
- Our later attempts with workspace listeners/build/touch are outside the real failure point: they try to repair the consumer after
  the linked producer temporarily disappeared.

Better direction:

- Stop treating EDT's current direct scope as the only source of truth for linked projects.
- Maintain a last-known-good linked scope snapshot per project/scope kind.
- On `addTypeItemScope` / `addPropertyScope`, update that stable snapshot.
- On `clearTypeItemsScopes` / `clearPropertyScopes`, do not immediately erase the stable snapshot.
- When composing context for another extension, use the current direct scope if available; otherwise fall back to the last-known-good
  snapshot until the producer publishes a fresh scope.
- This should turn "full context loss" into "temporarily stale but still usable linked context", which matches the expected behavior
  during an in-progress background rebuild.

## 2026-06-13 - Use Last Stable Linked Project Scope When EDT Direct Scope Is Null

User decision:

- Do not wait/block inside scope lookup.
- Try caching the linked project scope and use the cached version when EDT temporarily returns `null`.

Implementation attempt:

- Add `stableProjectScopes` in `ContextLinksCachedScopeProvider`, keyed by project name and scope kind (`TYPE_ITEM` /
  `PROPERTY`).
- Update stable project scope snapshots only when EDT gives a non-null direct scope through:
  `addTypeItemScope(...)`, `addPropertyScope(...)`, or a successful direct lookup.
- Do not clear stable snapshots on `clearTypeItemsScopes(...)` / `clearPropertyScopes(...)`.
- For linked project lookup only, `getDirectTypeItemScope(...)` / `getDirectPropertyScope(...)` now returns:
  - current EDT direct scope if non-null;
  - otherwise last stable scope for that linked project and kind.
- Remove the previous resource-change/touch dependency refresh attempt, so this build tests the cache fallback hypothesis without
  synthetic file touches.

Expected next log check:

- During `Extension2` rebuild, if EDT returns `null` for its direct scope while `Extension1` asks for linked context, log should show:
  `EDT Context Links stable scope fallback project=... kind=...`.
- `Extension1` should keep seeing at least the last stable `Extension2` context instead of losing the linked context completely.

Build:

- First `mvn package -DskipTests` attempt failed at `2026-06-13 17:40:22 +04:00`.
- Java compilation completed, but repository packaging failed because Maven could not delete the locked update-site zip:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`.
- After the update-site zip was released, `mvn package -DskipTests` completed successfully at
  `2026-06-13 17:41:07 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `104668` bytes.
