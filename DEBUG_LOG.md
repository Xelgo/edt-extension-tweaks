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
methods=[–¢–µ—Å—Ç]
```

`Ext1_CommonModule` context:

```text
line 11540
[module.context.provider] project=Configuration.Rest1
moduleUri=platform:/resource/Configuration.Rest1/src/CommonModules/Ext1_CommonModule/Module.bsl
owner=Ext1_CommonModule
provider=com._1c.g5.v8.dt.internal.bsl.contextdef.BslModuleContextDefExtensionCommonModule
context=RefContextDelegatingContextDefImpl{properties=1,methods=0,allProperties=1,allMethods=2,refs=1}
methods=[–¢–µ—Å—Ç, –¢–µ—Å—Ç2]
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
sample=[Doc, ThisObject, –≠—Ç–æ—Ç–û–±—ä–µ–∫—Ç, Ext2_CommonModule, Ext2_CommonModule, Ext2_CommonModule1, ...]
```

The sample includes `Ext2_*`, but does not include `Ext1_CommonModule`.

For `Configuration.Rest1`:

```text
line 11666
[bsl.scope] project=Configuration.Rest1
context=FeatureEntry reference=feature
sample=[test4, test2, test3, test, ext2, ThisObject, –≠—Ç–æ—Ç–û–±—ä–µ–∫—Ç, Ext1_CommonModule, ...]
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
methods=[–¢–µ—Å—Ç]
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
moduleUri=platform:/resource/Configuration.Rest2/src/CommonModules/Ext2_–û–±—â–∏–π–ú–æ–¥—É–ª—å3/Module.bsl
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

- For a fresh module such as `Ext2_–û–±—â–∏–π–ú–æ–¥—É–ª—å3`, `[module.context.provider]` may still show `allMethods=0`.
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
  `[module.context.provider]` for `Ext2_–û–±—â–∏–π–ú–æ–¥—É–ª—å4` shows `allMethods=1`, `methods=[–¢–µ—Å—Ç]`, and a BM resource-backed
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
- Add a navigator property tester so the **–ù–∞—Å—Ç—Ä–æ–∏—Ç—å –∫–æ–Ω—Ç–µ–∫—Å—Ç EDT** menu item is visible only for extension projects.
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

- Adding an attribute to `Extension2` (`Document –†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç.–†–µ–∫–≤–∏–∑–∏—Ç3`) starts an EDT background model rebuild.
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
  `changed=[...–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2] ... dependentProjects/previous projects include [...–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ]`.

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

## 2026-06-13 - Query Constructor / QL Context Research

User direction:

- Current BSL context linking is acceptable enough to continue.
- Need to research the EDT Query Constructor ("–ö–æ–Ω—Å—Ç—Ä—É–∫—Ç–æ—Ä –∑–∞–ø—Ä–æ—Å–æ–≤").
- Goal: query constructor/table-field suggestions should see the same linked extension context as BSL code.

Relevant EDT bundles in the 2025.2 target platform:

- `com._1c.g5.v8.dt.ql_22.1.0.v202605050943.jar`
- `com._1c.g5.v8.dt.ql.model_11.0.0.v202605050943.jar`
- `com._1c.g5.v8.dt.ql.dcs_16.0.100.v202605050943.jar`
- `com._1c.g5.v8.dt.ql.dcs.model_5.0.300.v202605050943.jar`
- `com._1c.g5.v8.dt.dcs_21.1.1.v202605050943.jar`
- `com._1c.g5.v8.dt.dcs.model_9.0.100.v202605050943.jar`

Important findings:

- `com._1c.g5.v8.dt.ql` declares extension point
  `com._1c.g5.v8.dt.ql.qlRuntimeModuleExtension`.
- The QL extension point looks analogous to BSL's `bslRuntimeModuleExtension`, but the QL jar does not contain the referenced
  `schema/qlRuntimeModuleExtension.exsd`; BSL does contain its schema.
- `QlRuntimeModule` binds `IQlCachedScopeProvider` and `IContainer.Manager`.
- `QlScopeProvider` contains injected fields:
  - `IQlCachedScopeProvider cacheScopeProvider`;
  - `IResourceLookup resourceLookup`;
  - standard QL mapper/type/dynamic-db-view services.
- `QlScopeProvider.getAllowedDbViewStatic(context)` does:
  - resolve current `IDtProject` through `IResourceLookup.getDtProject(context)`;
  - call `IQlCachedScopeProvider.getDbViewScope(dtProject)`;
  - if missing, build standard scope through the delegate for
    `QlPackage.Literals.ABSTRACT_QUERY_SCHEMA_TABLE__TABLE_DB_VIEW`;
  - then save it through `IQlCachedScopeProvider.addDbViewScope(dtProject, scope)`.
- `QlCachedScopeProvider` has a very small public surface:
  - `addDbViewScope(IDtProject, IScope)`;
  - `getDbViewScope(IDtProject)`;
  - `clearDbViewScopes(IDtProject)`;
  - per-query operator scope methods `setDbViewScope(String, String, IScope)` / `getScope(String, String)`.
- `com._1c.g5.v8.dt.ql.dcs` has `QlDcsRuntimeModule` and `QlDcsScopeProvider`.
  `QlDcsScopeProvider` extends `QlScopeProvider`, so the static db-view path still flows through the QL cache provider.
- `com._1c.g5.v8.dt.dcs` has `DcsDataSetInfoV8LocalQuery`, which keeps an injected `IQlCachedScopeProvider qlCacheScope` and
  works with `QlMapper`, `QlCheckerExpression`, dynamic db-view computer and query schema model.
- BSL string-literal content type computers identify query constructor contexts:
  - `QueryCtorTypeComputer` for `Query`;
  - `QueryDcsCtorTypeComputer` for `QueryBuilder`, `ReportBuilder`, `QueryWizard`;
  - `QueryDcsPropertyTextTypeComputer` for `Text` / `Query` / `QueryText` properties of relevant query/DCS objects.

Current hypothesis:

- The constructor starts from BSL string literal classification, but table/field model building is QL/QL-DCS/DCS.
- The lowest-risk first injection point is not the query constructor UI, but `IQlCachedScopeProvider`.
- A custom QL cached scope provider can compose the current project's db-view scope with configured linked projects' db-view scopes,
  using the same per-project settings and last-stable fallback idea as BSL.
- This should affect:
  - QL text validation/content assist;
  - QL-DCS syntax used by query constructor/report builder/query wizard;
  - DCS data set info building that uses `IQlCachedScopeProvider`.

Implementation candidate:

- Add QL/DCS bundle dependencies to the plugin manifest.
- Add a `com._1c.g5.v8.dt.ql.qlRuntimeModuleExtension` contribution.
- Implement `ContextLinksQlRuntimeModule extends AbstractGenericModule`.
- Bind `IQlCachedScopeProvider` to a custom `ContextLinksQlCachedScopeProvider extends QlCachedScopeProvider`.
- In that provider:
  - remember last stable `IDtProject -> IScope` db-view scopes on non-null `addDbViewScope`;
  - do not drop the stable snapshot on `clearDbViewScopes`;
  - for extension projects with configured linked context, return a composite db-view scope:
    own current/stable db-view scope first, then linked current/stable scopes;
  - keep per-query operator scope methods delegated unchanged.
- Avoid replacing `QlScopeProvider` at first; overriding it is a larger blast radius.

Open risks:

- Need confirm whether EDT actually mixes `qlRuntimeModuleExtension` in the same OSGi injector path as BSL. The extension point is
  declared, but direct `QlStandaloneSetup` bytecode does not show the extension merge.
- If the query constructor uses a separate QL-DCS injector that does not consume the QL runtime extension point, we may need a second
  path through DCS/QL-DCS service registration.

## 2026-06-13 - First QL Db-View Scope Injection Attempt

Implementation attempt:

- Add `com._1c.g5.v8.dt.ql` as a required bundle.
- Register `com._1c.g5.v8.dt.ql.qlRuntimeModuleExtension` in `plugin.xml`.
- Add `ContextLinksQlRuntimeModule`, binding `IQlCachedScopeProvider` to `ContextLinksQlCachedScopeProvider`.
- Add `ContextLinksQlCachedScopeProvider extends QlCachedScopeProvider`.
- Keep QL's standard scope provider untouched.
- In the custom QL cache provider:
  - call `super.getDbViewScope(project)` for the current project;
  - if the current project has no QL db-view scope yet, return `null` so EDT still builds the standard scope normally;
  - for extension projects with configured linked context, return a composite db-view scope:
    current project first, linked projects after it;
  - when linked project direct QL db-view scope is temporarily `null`, use the last stable linked db-view scope;
  - do not clear stable db-view snapshots on `clearDbViewScopes`.

Expected behavior:

- Query text and Query Constructor should see tables/fields from configured linked extension projects through the same QL db-view
  scope path used by `QlScopeProvider`.
- If EDT rebuilds a linked extension and temporarily clears its QL db-view scope, other extensions should keep the last stable
  linked query tables/fields instead of dropping the whole linked query context.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 17:58:09 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `112926` bytes.

Next log check:

- On EDT start/open query constructor, look for:
  - `EDT Context Links QL runtime module constructed`;
  - `EDT Context Links QL cached scope provider constructed`.
- With debug logging enabled, useful signals are:
  - `EDT Context Links QL db-view scope: project=... configured=... added=... missing=...`;
  - `EDT Context Links QL stable db-view fallback project=...`.

## 2026-06-13 - First QL Runtime Extension Attempt Failed

User-visible result after installing `261cd34`:

- Query Constructor did not change.
- It still showed only the current extension's model.

Fresh EDT workspace log:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
```

Important error:

```text
Failed to add extension hover for class com._1c.g5.v8.dt.right.ql.ui.RightQlExecutableExtensionFactory:org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover
Caused by: java.lang.AssertionError: Right Ql Runtime Module extension cannot be more then one
    at com._1c.g5.v8.dt.internal.right.ql.ui.RightQlUiPlugin.getRuntimeModuleExtension(RightQlUiPlugin.java:130)
```

Additional runtime-platform inspection:

- The installed EDT has QL BM bundles that were not visible in the earlier target-cache-only research.
- `com._1c.g5.v8.dt.ql.bm` already contributes:

```xml
<extension point="com._1c.g5.v8.dt.ql.qlRuntimeModuleExtension">
  <qlRuntimeModuleExtension module="com._1c.g5.v8.dt.ql.bm.BmAwareQlModule"/>
</extension>
```

Conclusion:

- `com._1c.g5.v8.dt.ql.qlRuntimeModuleExtension` is a singleton-style hook in this EDT runtime.
- The first attempt added a second QL runtime module, so EDT rejected the QL runtime extension set.
- Because the existing BM-aware QL module already owns that extension point, this plugin cannot safely bind
  `IQlCachedScopeProvider` through `qlRuntimeModuleExtension`.

Recovery action:

- Remove the plugin's QL runtime extension contribution.
- Remove `ContextLinksQlRuntimeModule` and `ContextLinksQlCachedScopeProvider`.
- Remove the direct `com._1c.g5.v8.dt.ql` bundle dependency.
- Keep this failed attempt documented because it rules out the most obvious QL hook.

Next research direction:

- Study the existing `com._1c.g5.v8.dt.ql.bm.scoping.QlGlobalScopeProvider`.
- It extends `PlatformAwareGlobalScopeProvider` and adds only the parent configuration scope plus current resource scope.
- It does not include sibling extension projects, which matches the Query Constructor symptom.
- A safer next attempt should target the BM/global metadata scope path that `BmAwareQlModule` already uses, not add another QL
  runtime module.

Recovery build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 18:06:56 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `115682` bytes.

## 2026-06-13 - QL BM Scope Wrapper Experiment

Further runtime-platform research:

- `BmAwareQlModule` is the existing singleton QL runtime module.
- It binds `IGlobalScopeProvider` to `com._1c.g5.v8.dt.ql.bm.scoping.QlGlobalScopeProvider`.
- `QlGlobalScopeProvider` builds this scope order:
  - platform scope;
  - parent configuration scope for extension projects;
  - current QL resource project scope.
- It never adds sibling extension projects configured by this plugin.

Lower-level provider chain:

- `QlGlobalScopeProvider` injects `IV8GlobalScopeProvider`.
- The registered EDT service is `com.e1c.g5.dt.core.legacy.internal.scoping.V8GlobalScopeProvider`.
- That service delegates to `IDtProjectGlobalScopeProvider`.
- `IDtProjectGlobalScopeProvider` has `getScope(IDtProject, ...)` / `getScope(Resource, ...)`, while
  `IV8GlobalScopeProvider` also exposes `getScope(IProject, ...)`.

Implementation experiment:

- Do not add another `qlRuntimeModuleExtension`.
- Register a narrow `IV8GlobalScopeProvider` wrapper with higher OSGi service ranking.
- The wrapper delegates all calls to the original EDT provider.
- Only for resources whose runtime class starts with `com._1c.g5.v8.dt.ql.` and whose project is an extension with configured
  context links, the wrapper composes:
  - EDT's own QL BM scope;
  - `delegate.getScope(linkedIProject, reference, filter)` for each configured linked extension project.
- The wrapper is registered from both UI startup and BSL runtime module construction so it is available early enough.

Risk:

- This creates a second OSGi service of type `IV8GlobalScopeProvider`.
- Peaberry-based Guice service lookup should choose the highest-ranked service, but any code using strict
  `ServiceAccess.get(IV8GlobalScopeProvider.class)` could dislike duplicate services.
- If that happens, this experiment should be reverted or replaced with a more internal hook.

Build note:

- Direct Java import of `com._1c.g5.v8.dt.core.scoping.IV8GlobalScopeProvider` failed at
  `2026-06-13 18:15:53 +04:00` because Tycho treats that type as non-API:

```text
Access restriction: The type 'IV8GlobalScopeProvider' is not API
```

- The experiment was rewritten as a reflection-based OSGi dynamic proxy registered by service class name.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 18:18:23 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `121218` bytes.

Expected next log check:

- EDT Error Log should not show the previous `Right Ql Runtime Module extension cannot be more then one`.
- On Query Constructor opening for an extension project, log should show:
  `EDT Context Links QL BM global scope wrapper registered`
  and
  `EDT Context Links QL BM scope project=... linked=[...] skipped=[...]`.

Follow-up after installing `e3d53ca`:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
Length: 101601
LastWriteTime: 2026-06-13 19:08:33 +04:00
```

Observed plugin entries:

```text
EDT Context Links QL BM global scope wrapper skipped: delegate unavailable
EDT Context Links QL BM global scope wrapper registered
```

Missing expected entry:

```text
EDT Context Links QL BM scope project=... linked=[...] skipped=[...]
```

Conclusion:

- The proxy eventually registered, and the previous singleton QL runtime assertion did not reappear.
- But there is no evidence that Query Constructor calls flowed through the proxy.
- Most likely the QL/BM injector captured the original `IV8GlobalScopeProvider` before this plugin registered its wrapper.

Next adjustment:

- Add a real bundle activator and register the proxy from bundle start.
- Contribute the bundle to `com._1c.g5.wiring.serviceProvider`, so EDT wiring has a reason to start it earlier.
- Create the proxy without requiring the original delegate to already exist; resolve the delegate lazily on each call.
- Add a one-per-resource-class diagnostic:
  `EDT Context Links QL BM provider call resource=... project=... ql=...`.

Implementation:

- Add `Bundle-Activator: ru.xelgo.edt.contextlinks.core.ContextLinksPlugin`.
- Add `ContextLinksPlugin extends Plugin`, registering the QL BM proxy from `start(...)`.
- Add a `com._1c.g5.wiring.serviceProvider` contribution for `ru.xelgo.edt.contextlinks.ui`.
- Change proxy creation so it loads the `IV8GlobalScopeProvider` interface by name and registers immediately, even if the original
  EDT provider is not yet available.
- Keep delegate resolution lazy per invocation.

Build:

- First sandboxed build failed before compilation because Maven/Tycho dependency resolution attempted network access and hit
  `Access is denied`.
- Re-run outside sandbox completed successfully at `2026-06-13 19:10:45 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `121625` bytes.

Follow-up after installing `6cdcfb6`:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
Length: 224359
LastWriteTime: 2026-06-13 19:12:59 +04:00
```

Observed failure:

```text
EDT Context Links failed to create QL BM global scope wrapper
java.lang.ClassNotFoundException:
com._1c.g5.v8.dt.core.scoping.IV8GlobalScopeProvider cannot be found by ru.xelgo.edt.contextlinks.ui_1.0.0.v202606131510
```

Conclusion:

- The activator/serviceProvider path did start the plugin earlier.
- But the reflection proxy tried to load the non-API `IV8GlobalScopeProvider` interface from this plugin's classloader.
- That package is intentionally not visible to this plugin bundle.

Next adjustment:

- Load the service interface class from the installed `com._1c.g5.v8.dt.core` OSGi bundle via `bundle.loadClass(...)`.
- Keep the proxy registered by service class name, still avoiding direct Java imports of the non-API type.

Implementation:

- `ContextLinksV8GlobalScopeProviderProxy.loadServiceClass(...)` now scans installed OSGi bundles and loads
  `com._1c.g5.v8.dt.core.scoping.IV8GlobalScopeProvider` from bundle `com._1c.g5.v8.dt.core`.
- This avoids using the plugin bundle classloader for a non-API class it cannot see.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 19:14:24 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `124488` bytes.

Follow-up after installing `ff5a137`:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
Length: 350828
LastWriteTime: 2026-06-13 19:15:59 +04:00
```

Important result:

```text
EDT Context Links QL BM global scope wrapper registered
EDT Context Links QL BM provider call resource=com._1c.g5.v8.bm.core.internal.BmResource project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è ql=false
EDT Context Links QL BM provider call resource=com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource project=NULL ql=true
```

Conclusion:

- The classloader problem is fixed.
- The wrapper is now on the QL/DCS execution path.
- The remaining immediate blocker is project resolution: `QlDcsResource` reaches the wrapper, but `ContextLinks.getProject(uri)`
  returns `NULL`.
- Because project is `NULL`, the wrapper cannot check extension settings and cannot add sibling extension scopes.

Next adjustment:

- Resolve the project for QL/DCS resources through EDT `IResourceLookup.getProject(resource)` and
  `IResourceLookup.getPlatformResource(resource)`, falling back to URI only when possible.
- Add the resource URI to the one-per-resource diagnostic to understand future embedded resource shapes.

Implementation:

- `ContextLinksV8GlobalScopeProviderProxy.workspaceProject(...)` now first uses the existing URI lookup, then falls back to
  EDT `IResourceLookup.getProject(resource)` and `IResourceLookup.getPlatformResource(resource)`.
- QL/BM diagnostics now include the resource URI to identify embedded QL/DCS resource shapes when project lookup still fails.

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 19:17:20 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `122400` bytes.

Immediate log check after restart/install attempt:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
Length: 503506
LastWriteTime: 2026-06-13 19:18:32 +04:00
```

Observed:

```text
NoSuchFileException: ...\target\ru.xelgo.edt.contextlinks.repository-v*.zip
No repository found containing: osgi.bundle,ru.xelgo.edt.contextlinks.ui,1.0.0.v202606131517
EDT Context Links QL BM global scope wrapper registered
```

Conclusion:

- EDT/p2 still has several old update-site zip URLs cached or configured (`repository-v1.zip`, `repository-v2.zip`, etc.).
- The plugin did start, because the QL BM wrapper registration message is present.
- There are no fresh `QL BM provider call` lines after that registration yet, so this log does not prove whether the new
  `IResourceLookup` fallback resolves `QlDcsResource` projects. Need one more query-constructor open/check after this build.

Follow-up after opening/checking query constructor with `90f4cf0`:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
Length: 617217
LastWriteTime: 2026-06-13 19:19:38 +04:00
```

Important result:

```text
EDT Context Links QL BM global scope wrapper registered
EDT Context Links QL BM provider call resource=com._1c.g5.v8.bm.core.internal.BmResource project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è ql=false uri=bm://–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è/Configuration
EDT Context Links QL BM provider call resource=com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2 ql=true uri=platform:/resource/–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2/querywizard_1781363970873.qldcs
EDT Context Links QL BM scope project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2 linked=[–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ] skipped=[–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è]
```

Conclusion:

- The `IResourceLookup` fallback fixed the immediate `QlDcsResource project=NULL` problem.
- The query constructor / QL DCS path now reaches our BM scope wrapper with the correct extension project.
- For `–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2`, the wrapper adds the linked sibling extension `–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ` and skips the base
  configuration as intended.
- Remaining uncertainty: this only proves scope injection for the opened `–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2` query constructor resource. Need functional
  EDT UI check to confirm whether the query constructor suggestions now show sibling-extension objects.
- Current log still contains unrelated EDT/p2 noise from stale update-site zip URLs (`repository-v*.zip`) and 1C service
  authentication failures; these are not plugin runtime failures.

Fresh log after deleting `.metadata/.log` and checking again:

```text
C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main\.metadata\.log
Length: 272973
LastWriteTime: 2026-06-13 19:22:44 +04:00
```

Important plugin lines:

```text
EDT Context Links QL BM global scope wrapper registered
EDT Context Links startup warm-up scheduled
EDT Context Links startup warm-up pass=1 projects=[–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2, –ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ]
EDT Context Links startup warm-up build pass=1 project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2
EDT Context Links startup warm-up build pass=1 project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ
EDT Context Links QL BM provider call resource=com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2 ql=true uri=platform:/resource/–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2/querywizard_1781364155235.qldcs
EDT Context Links QL BM scope project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2 linked=[–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ] skipped=[–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è]
EDT Context Links startup warm-up pass=2 projects=[–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2, –ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ]
```

Conclusion:

- The successful QL/DCS project lookup is reproducible on a fresh log.
- The wrapper is active before the query constructor scope call.
- The linked extension scope is still being composed for `–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2`.
- The log size problem is not caused by our diagnostics now; most volume is EDT/p2 repeatedly trying stale update-site zip URLs
  like `ru.xelgo.edt.contextlinks.repository-v*.zip` plus 1C service authentication errors.

## 2026-06-13 - Targeted QL Probe for `–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç`

User scenario:

- Open module `–†–∞—Å—à2_–û–±—â–∏–π–ú–æ–¥—É–ª—å` in `–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ2`.
- Open Query Constructor from that module.
- Current Query Constructor only shows `–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç` and `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫`.
- It should also show objects from `–†–∞—Å—à–∏—Ä–µ–Ω–∏–µ1`, first target for diagnosis: `–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç`.

Hypothesis to split:

- If `–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç` is present in the linked/composite QL BM scope, but Query Constructor UI still does not show it, then the
  next missing layer is likely a QL-DCS/querywizard db-view model or cache built above the global BM scope.
- If `–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç` is absent from the linked scope itself, then the current `EReference`/filter used by QL's BM provider
  excludes linked extension metadata before UI gets a chance to show it.

Implementation:

- Add a temporary targeted QL probe to `ContextLinksV8GlobalScopeProviderProxy`.
- For every QL BM composition, scan up to 20000 elements in:
  - own scope;
  - linked extension scope;
  - final composite scope.
- Log whether target `–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç` is found, how many elements were scanned, whether scanning was truncated, and up to five
  matching descriptions.

Expected log lines after opening Query Constructor:

```text
EDT Context Links QL probe phase=own ...
EDT Context Links QL probe phase=linked ... target=–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç found=...
EDT Context Links QL probe phase=composite ... target=–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç found=...
```

Build:

- `mvn package -DskipTests` completed successfully at `2026-06-13 19:27:45 +04:00`.
- Update site artifact:
  `repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip`
- Artifact size: `127151` bytes.

## 2026-06-13 - EDT Redeploy Automation

Goal:

- Avoid repeated manual install/restart steps while iterating on plugin builds.
- Target active EDT installation:
  `C:\Users\Xelgo\AppData\Local\1C\1cedtstart\installations\1C_EDT 2025.2\1cedt`
- Target workspace:
  `C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main`

Observed live processes:

```text
1cedt.exe  -data C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main
javaw.exe  -data C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main
1cedtstart.exe remains separate and should not be stopped by workspace redeploy.
```

Implementation:

- Added `tools/redeploy-edt-main.ps1`.
- The script can:
  - run Maven build unless `-SkipBuild` is passed;
  - find and close only EDT processes whose command line contains the `Main` workspace path;
  - install `ru.xelgo.edt.contextlinks.feature.feature.group` from the repository zip through Eclipse p2 director;
  - delete the workspace `.metadata\.log`;
  - restart EDT with `-data ...\projects\Main`.
- `-DryRun` prints all intended steps without closing EDT or installing anything.
- If EDT does not close gracefully, the script stops and asks for `-ForceKill` instead of force-killing by default.

Verified:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File tools\redeploy-edt-main.ps1 -DryRun -SkipBuild
```

Dry-run correctly detected only the workspace EDT processes and prepared this p2 director command:

```text
1cedt.exe -nosplash -application org.eclipse.equinox.p2.director
  -repository jar:file:/.../ru.xelgo.edt.contextlinks.repository.zip!/
  -installIU ru.xelgo.edt.contextlinks.feature.feature.group
  -profile C__Users_Xelgo_AppData_Local_1C_1cedtstart_installations_1C_EDT 2025.2_1cedt
  -destination ...\1C_EDT 2025.2\1cedt
  -bundlepool C:\Users\Xelgo\.p2\pool
```

Follow-up after first real redeploy attempt:

- GUI `1cedt.exe` was not suitable for headless p2 director: it returned without updating `.p2\pool`.
- Console `1cedtc.exe` needs `java.exe` in `PATH`; otherwise it shows a Java Runtime Environment / JDK missing dialog.
- Added `C:\Program Files\1C\1CE\components\1c-edt-start-0.9.0+277-x86_64\jre\bin` to the user `PATH`.
- Verified:

```text
java -version
openjdk version "17.0.16" 2025-07-15 LTS
```

Script adjustments:

- Use `1cedtc.exe` for p2 director.
- Prepend the EDT Start JRE `bin` path to the script process `PATH` before running director.
- Add target-platform repositories as dependency sources:
  - `https://edt.1c.ru/downloads/releases/ruby/2025.2/`
  - `https://download.eclipse.org/releases/2023-12/`
  - `http://download.eclipse.org/cbi/updates/license/2.0.2.v20181016-2210`
- Restart EDT even if the p2 director step fails, then rethrow the install error so the failure is still visible.
- Use array arguments for the GUI restart so the `-vm` path with spaces is passed correctly.

Follow-up redeploy check:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File tools\redeploy-edt-main.ps1 -SkipBuild
```

Result:

- p2 director install succeeded with dependency repositories:

```text
–£—Å—Ç–∞–Ω–æ–≤–∫–∞ ru.xelgo.edt.contextlinks.feature.feature.group 1.0.0.v202606131532.
Add –∑–∞–ø—Ä–æ—Å –¥–ª—è EDT Context Links 1.0.0.v202606131532 ... –≤—ã–ø–æ–ª–Ω–µ–Ω
–û–ø–µ—Ä–∞—Ü–∏—è –∑–∞–≤–µ—Ä—à–µ–Ω–∞ –∑–∞ 26111 –º—Å–µ–∫.
```

- The new plugin bundle appeared in `.p2\pool`:

```text
C:\Users\Xelgo\.p2\pool\plugins\ru.xelgo.edt.contextlinks.ui_1.0.0.v202606131532.jar
```

- Restart with explicit `-vm C:\Program Files\...javaw.exe` still caused Eclipse launcher trouble because the path with spaces
  was not quoted reliably by `Start-Process`.
- Because `java.exe` is now in the user `PATH`, remove the explicit `-vm` argument from restart and let the launcher resolve Java.
- Manual restart without `-vm` succeeded. Fresh workspace log contains:

```text
EDT Context Links QL BM global scope wrapper registered
EDT Context Links startup warm-up scheduled
```

Follow-up during automated UI-check setup:

- Functional screenshot check showed Query Constructor opened from `–†–∞—Å—à2_–û–±—â–∏–π–ú–æ–¥—É–ª—å`; under `–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫–∏` only
  `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` was visible. `–†–∞—Å—à1_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` was still absent.
- Expected `QL probe` lines were missing from the log even though `QL BM scope` was logged.
- Root cause for missing probe: EDT runtime was still loading old bundle `1.0.0.v202606131517` from
  `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`, while p2 had downloaded newer
  `1.0.0.v202606131532` into `.p2\pool\plugins`.

Redeploy automation adjustments:

- `redeploy-edt-main.ps1` now uninstalls `ru.xelgo.edt.contextlinks.feature.feature.group` before installing it again.
- Normal build path now runs `mvn clean package -DskipTests` to avoid stale class files from earlier experiments being packed
  into the plugin jar.
- After p2 install, the script synchronizes `bundles.info` to the newest
  `C:\Users\Xelgo\.p2\pool\plugins\ru.xelgo.edt.contextlinks.ui_*.jar`.
- `bundles.info` must be written as UTF-8 without BOM. A BOM caused:

```text
java.lang.IllegalArgumentException: Line does not contain at least 5 tokens: –ø¬ª—ó#encoding=UTF-8
```

- Current manual fix verified:

```text
FIRST_BYTES=23 65 6E
ru.xelgo.edt.contextlinks.ui,1.0.0.v202606131532,file:/C:/Users/Xelgo/.p2/pool/plugins/ru.xelgo.edt.contextlinks.ui_1.0.0.v202606131532.jar,4,false
```

Computer Use status:

- Installed plugin was found at:
  `C:\Users\Xelgo\.codex\plugins\cache\openai-bundled\computer-use\26.609.41114`
- The current Codex thread still did not expose direct computer-use tools through `tool_search`.
- Attempting the official `scripts/computer-use-client.mjs` bootstrap through Node REPL failed with:

```text
Package subpath './dist/project/cua/sky_js/src/targets/windows/internal/computer_use_client_base.js'
is not defined by "exports" in ...\@oai\sky\package.json
```

Conclusion:

- Need a Codex restart / plugin runtime reload before using Computer Use reliably in this thread.
- Until Computer Use starts, avoid continuing fragile foreground PowerShell mouse automation.

Follow-up after retrying Computer Use in the next user turn:

- Re-read the bundled `computer-use` skill and retried the official `scripts/computer-use-client.mjs` bootstrap.
- Reset the JavaScript runtime and retried once more.
- Both attempts failed before `sky.list_apps()` with the same bundled runtime export error:

```text
Package subpath './dist/project/cua/sky_js/src/targets/windows/internal/computer_use_client_base.js'
is not defined by "exports" in
C:\Users\Xelgo\AppData\Local\OpenAI\Codex\runtimes\cua_node\789504f803e82e2b\bin\node_modules\@oai\sky\package.json
```

Conclusion:

- Computer Use is still unavailable in this Codex runtime.
- This is not an EDT/plugin issue; the failure happens before Windows app discovery.
- Do not continue EDT UI automation through fragile foreground PowerShell/mouse scripts unless explicitly requested.

Attempt in progress, 2026-06-14:

- Added `ContextLinksServiceRegistrars.ensureRegistered()` as the common entrypoint for OSGi service wrappers.
- Call it from plugin startup, UI startup, BSL runtime module construction, and `ContextLinksCachedScopeProvider` construction.
- Added registration diagnostics for the QL BM scope wrapper so the workspace log shows whether registration was skipped, failed, or completed.
- Added an `IModelObjectAdopter` wrapper that only acts from the Query Wizard adoption stack:
  - `isAdopted(foreignExtensionObject, currentExtension)` returns `true`.
  - `adoptAndAttach(List, currentExtension, monitor)` filters foreign-extension objects before delegating.
  - Base configuration metadata is not filtered and should still use EDT's normal adoption path.

Follow-up before handoff, 2026-06-13:

- Current deployed bundle during the last check was `ru.xelgo.edt.contextlinks.ui 1.0.0.v202606131708`.
- Good state preserved:
  - `Ctrl+Alt+Q`, then `Tab`, then right arrows opens Query Wizard and shows linked `–†–∞—Å—à1_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫`.
  - Linked catalog fields are visible under the linked root.
  - `ContextLinksModuleContextDefService.ensureFallbackContextDef` no longer mutates BM model inside read-only validation transactions.
- Current bug is not fixed:
  - Clicking/adding linked `–†–∞—Å—à1_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` into the center `Tables` area still gives the wrong icon/semantics.
  - It is still treated like a requisite/nested db view element, not like a real top-level table.
- Important log clue after adding it:

```text
java.lang.IllegalArgumentException: Expected an instance of BmObject, but was
ru.xelgo.edt.contextlinks.core.ContextLinksV8GlobalScopeProviderProxy$FriendlyDbViewInvocationHandler
    at com._1c.g5.v8.bm.core.internal.transaction.BmPlatformTransaction.toTransactionObject(...)
    at com._1c.g5.v8.dt.ql.resource.DynamicDbViewFieldComputer.extendDbViewElementIfNecessary(...)
```

- My next thought:
  - The `CurrentProjectFriendlyDescription` dynamic proxy is acceptable for the source tree filter, but it must not be written into the QL query schema.
  - `SourcesEditProvider.add(Object)` takes `TreeItem.getData()` and passes the resulting `DbViewElement` to `QlUtil.createQuerySchemaTable`.
  - That means the source tree currently exposes the proxy as tree item data, and the proxy leaks into `QuerySchemaTable.tableDbView`.
  - Next attempt should avoid using a dynamic proxy as the actual `TreeItem` data for add operations.
  - Possible routes:
    - Prefer returning the real linked `DbViewDef` from tree data and make only `QueryWizardServiceUtils.isObjectBelongsToCurrentProject` see the current-project surrogate.
    - Or introduce a wrapper that EDT can unwrap before `SourcesEditProvider.add`, but this is harder without patching EDT internals.
    - Check whether the membership filter can be bypassed by wrapping `IEObjectDescription` or `getMdObject()` only during `TablesAndFieldsTab$1.select`, while `getEObjectOrProxy()` returns the real linked object in all other call stacks.
  - Also fix `Object` methods on any remaining proxy (`equals`, `hashCode`, `toString`), because `method.invoke(this, args)` for `Object` methods currently lets the handler identity leak.

Follow-up, 2026-06-14 attempt `v202606132036`:

- Built and redeployed `ru.xelgo.edt.contextlinks.feature 1.0.0.v202606132036`.
- This attempt removed the broad `projectAnchor` projection that had broken linked objects.
- Observed after redeploy:
  - `–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏` fields are visible again in the database tree.
  - `–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç` and `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` are still absent from the Query Wizard database tree opened from `–†–∞—Å—à1_–û–±—â–∏–π–ú–æ–¥—É–ª—å`.
  - `–†–∞—Å—à2_–†–µ–∫–≤–∏–∑–∏—Ç` is still collected as an adoption candidate when pressing `OK` from `–†–∞—Å—à1`; EDT still wants to add it to `–†–∞—Å—à1`, which must not happen for metadata owned by another extension.
  - The middle table list can still show `–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏`, `–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏1`, etc. when the user adds fields from the database tree.
- Log clue:
  - BSL context-link logs are present (`cache.module.add`, `settings.read`, etc.).
  - There is no `EDT Context Links QL BM global scope wrapper registered` line and no `EDT Context Links QL probe` line in the current workspace log.
  - This suggests the BSL scope wrapper is active, but the QL global-scope wrapper is not reliably registered on startup.
- Next attempt:
  - Register service wrappers from a class that is definitely constructed during BSL scope setup (`ContextLinksCachedScopeProvider`) in addition to plugin startup.
  - Add explicit registration diagnostics for wrapper services.
  - Add an `IModelObjectAdopter` wrapper: inside `QueryWizardAdoptSupport`, treat objects owned by a different `IExtensionProject` as already adopted, so Query Wizard skips the "add to current extension" prompt for `–†–∞—Å—à2_*` fields while keeping normal adoption for base configuration objects.

Follow-up, 2026-06-14 attempt `v202606132052`:

- Built and redeployed `ru.xelgo.edt.contextlinks.feature 1.0.0.v202606132052`.
- Code added:
  - common `ContextLinksServiceRegistrars.ensureRegistered()` entrypoint;
  - calls from plugin startup, UI startup, BSL runtime module and cached scope provider construction;
  - QL wrapper registration diagnostics;
  - experimental `IModelObjectAdopter` wrapper for Query Wizard adoption filtering;
  - `redeploy-edt-main.ps1` now force-kills EDT workspace processes immediately instead of first waiting for graceful window close.
- User-visible result is bad:
  - `–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç` and `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` still do not appear in Query Wizard from `–†–∞—Å—à1`.
  - `–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏` duplicate/table selection bug remains.
  - `OK` in Query Wizard regressed and no longer completes normally.
- Fresh workspace log after the failed check contains BSL/plugin debug entries, but no:
  - `EDT Context Links QL BM global scope wrapper registered`;
  - `EDT Context Links QL BM provider call`;
  - `EDT Context Links QL probe`;
  - `EDT Context Links model object adopter wrapper registered`;
  - `EDT Context Links adoption.skipForeignExtension`.
- Installed jar and `bundles.info` were checked:
  - `.p2\pool\plugins\ru.xelgo.edt.contextlinks.ui_1.0.0.v202606132052.jar` exists;
  - `bundles.info` points to that jar;
  - bytecode of `ContextLinksCachedScopeProvider` calls `ContextLinksServiceRegistrars.ensureRegistered()`.
- Conclusion:
  - This attempt did not restore the previously working QL wrapper path documented around `v202606131517`/`v202606131532`.
  - The `IModelObjectAdopter` idea should be removed or disabled until the base Query Wizard `OK` path is stable again.
  - Next attempt should follow the successful historical path: make `IV8GlobalScopeProvider` wrapper registration observable again, then address duplicates/adoption separately.

Recovery attempt in progress, 2026-06-14:

- Removed the experimental `IModelObjectAdopter` runtime registration path.
- Deleted `ContextLinksServiceRegistrars` and returned direct calls to `ContextLinksV8GlobalScopeProviderRegistrar.ensureRegistered()`
  from plugin startup, UI startup, BSL runtime module construction, and `ContextLinksCachedScopeProvider` construction.
- Keep the redeploy script's immediate EDT force-kill change.
- Expected recovery check:
  - `OK` should return to the previous behavior instead of freezing/failing;
  - workspace log should again show `EDT Context Links QL BM global scope wrapper registered`.

Follow-up after redeploying recovery build `v202606132100`:

- `EDT Context Links QL BM global scope wrapper registered` is back in the fresh workspace log.
- `Ctrl+Alt+Q` from `–†–∞—Å—à1_–û–±—â–∏–π–ú–æ–¥—É–ª—å` reaches the QL wrapper again.
- The QL probe shows the composite contains linked `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` and `–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç`.
- New concrete failure while opening Query Wizard from the existing query text:

```text
java.lang.IllegalArgumentException: Expected an instance of BmObject, but was
com._1c.g5.v8.dt.metadata.dbview.impl.DbViewTableDefImpl@...
    at com._1c.g5.v8.dt.qw.ui.controls.QueryWizardControl.setQueryText(...)
```

- The probe explains why:
  - own scope has `Catalog.–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏`;
  - linked scope has another `Catalog.–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏`;
  - the previous `DeduplicatingRichestScope` could select the linked richer object for the same qualified name.
- This matches the user-visible duplicate table bug.
- Fix in progress:
  - replace "richest wins" QL scope dedupe with "current project wins; linked projects only fill missing names";
  - stop returning friendly dynamic proxies from `IEObjectDescription.getEObjectOrProxy()` during `QuerySchemaBuilder`, so proxies do not leak into the query schema model.

Follow-up after redeploying `v202606132104`:

- Built and redeployed successfully.
- Query Wizard opened from `–†–∞—Å—à1_–û–±—â–∏–π–ú–æ–¥—É–ª—å` without the previous `Expected an instance of BmObject` warning.
- Fresh log shows the wrapper is active:

```text
EDT Context Links QL BM provider call resource=com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource
project=–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à1 ql=true
```

- Composite QL scope now has current project objects first:
  - `Catalog.–†–∞—Å—à1_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫`;
  - `Document.–†–∞—Å—à1_–î–æ–∫—É–º–µ–Ω—Ç`;
  - `Catalog.–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫_–∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏` from `–ö–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏—è.–†–∞—Å—à1`;
  - then linked unique objects `Catalog.–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` and `Document.–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç`.
- This confirms the `–†–∞—Å—à2_*` objects are back in the QL context and the same-name base catalog no longer shadows the current project with the linked extension object.
- Remaining work:
  - re-test a clean field-selection scenario in UI;
  - address the adoption prompt for actual foreign-extension fields after the base Query Wizard open path stays stable.

Attempt in progress, 2026-06-14:

- User confirmed `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫` and `–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç` are visible when the Query Wizard button hiding base-configuration
  fields is toggled off. They are gray, which is acceptable.
- Actual remaining defect: after selecting linked extension objects/fields, Query Wizard shows:

```text
–ó–∞–ø—Ä–æ—Å –∏—Å–ø–æ–ª—å–∑—É–µ—Ç –æ–±—ä–µ–∫—Ç—ã, –æ—Ç—Å—É—Ç—Å—Ç–≤—É—é—â–∏–µ –≤ —Ä–∞—Å—à–∏—Ä–µ–Ω–∏–∏ –∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏.
–î–æ–±–∞–≤–∏—Ç—å –∏—Å–ø–æ–ª—å–∑—É–µ–º—ã–µ –æ–±—ä–µ–∫—Ç—ã –≤ —Ä–∞—Å—à–∏—Ä–µ–Ω–∏–µ –∫–æ–Ω—Ñ–∏–≥—É—Ä–∞—Ü–∏–∏?
```

- Desired behavior:
  - objects/attributes from the base configuration may still be adopted into the current extension;
  - objects/attributes from another linked extension must not be adopted into the current extension;
  - this must apply both to root metadata objects like `–†–∞—Å—à2_–°–ø—Ä–∞–≤–æ—á–Ω–∏–∫`/`–†–∞—Å—à2_–î–æ–∫—É–º–µ–Ω—Ç` and their fields.
- New attempt:
  - add a separate `IModelObjectAdopter` OSGi wrapper, without restoring the failed common `ContextLinksServiceRegistrars`;
  - register the wrapper only after the original delegate service is available;
  - inside `QueryWizardAdoptSupport`, report foreign-extension objects as already adopted and filter them from
    `adoptAndAttach(List, IExtensionProject, monitor)`;
  - keep normal EDT adoption for base-configuration objects;
  - log `EDT Context Links adoption.skipForeignExtension ...` for every skipped foreign-extension object.

Follow-up for attempt `v202606132118`:

- Built and redeployed successfully.
- Fresh workspace log showed:

```text
EDT Context Links QL BM global scope wrapper registered
EDT Context Links model object adopter wrapper not registered: delegate unavailable
EDT Context Links model object adopter wrapper registered
```

- User-visible result is bad: pressing `OK` no longer closes Query Wizard.
- Root cause from workspace log:

```text
com._1c.g5.wiring.ServiceUnavailableException:
Multiple services were registered for com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter type
    at com._1c.g5.wiring.ServiceAccess.get(ServiceAccess.java:98)
    at com._1c.g5.v8.dt.internal.qw.ui.QueryWizardAdoptSupport.<init>(QueryWizardAdoptSupport.java:75)
```

- Conclusion:
  - `IModelObjectAdopter` cannot be wrapped as a second OSGi service because EDT's `ServiceAccess.get(...)` does not use
    service ranking here; it requires exactly one matching service.
  - This path must be reverted before continuing.
- Bytecode inspection of `QueryWizardAdoptSupport` explains the prompt:
  - `collectObjectsToAdopt()` reads `computeDbView.getMdObject()`;
  - then it calls `IV8ProjectManager.getProject(mdObject)`;
  - any md object whose project differs from the current `IExtensionProject` is placed into the adoption candidate set;
  - only after the user accepts the prompt does EDT call `IModelObjectAdopter.isAdopted(...)` and `adoptAndAttach(...)`.
- Next direction:
  - do not register another `IModelObjectAdopter`;
  - either influence `computeDbView.getMdObject()` only during `QueryWizardAdoptSupport.collectObjectsToAdopt()` for linked
    extension objects, or find a single-service replacement/in-place wrapping point.

Computer Use check, 2026-06-14:

- `tool_search` did not expose a direct Computer Use tool, but the bundled plugin exists at:

```text
C:\Users\USER\.codex\plugins\cache\openai-bundled\computer-use\26.602.40724
```

- The current script is present at `scripts\computer-use-client.mjs`.
- Bootstrap through the documented Node-backed entrypoint failed twice with:

```text
Computer Use native pipe path is unavailable
```

- Conclusion:
  - Computer Use is installed on disk but unavailable in this Codex turn because the native pipe environment is missing;
  - this is separate from EDT and the plugin code.

Follow-up after user restarted Computer Use/Codex plugin runtime:

- Retried the documented bootstrap twice after `@–ö–æ–º–ø—å—é—Ç–µ—Ä` was explicitly invoked.
- `tool_search` still exposed the Node-backed bootstrap path, not a separate direct Computer Use tool.
- Both bootstrap attempts still failed with:

```text
Computer Use native pipe path is unavailable
```

- Recovery redeploy after the failed adopter-service attempt:
  - removed the second `IModelObjectAdopter` service registration path;
  - rebuilt and redeployed `ru.xelgo.edt.contextlinks.feature 1.0.0.v202606132126`;
  - this should remove the immediate `Multiple services were registered for IModelObjectAdopter` regression.

## 2026-06-14 - Query Wizard Duplicate Tables And Foreign Extension Adoption, Attempt 1

User confirmed on screenshot `codex-clipboard-1445ce3c-b1e1-4f17-a7a0-3a226575bb64.png` that two defects remain in extension Query Wizard:

- Selecting attributes from the left **Database** tree under `—Ô‡‚Ó˜ÌËÍ_ÍÓÌÙË„Û‡ˆËË` keeps creating new table aliases: `—Ô‡‚Ó˜ÌËÍ_ÍÓÌÙË„Û‡ˆËË`, `—Ô‡‚Ó˜ÌËÍ_ÍÓÌÙË„Û‡ˆËË1`, `...2`, etc.
- Selecting metadata from another linked extension still triggers the **ƒÓ·‡‚ËÚ¸ ËÒÔÓÎ¸ÁÛÂÏ˚Â Ó·˙ÂÍÚ˚ ‚ ‡Ò¯ËÂÌËÂ ÍÓÌÙË„Û‡ˆËË?** prompt on `OK`; linked-extension objects must not be adopted into the current extension, while base configuration objects may still use normal EDT adoption.

Bytecode direction for this attempt:

- `SourcesEditProvider.findOrAddSource(...)` normalizes already-added `ExtendedDbViewTableDef` / `ExtendedDbViewFieldTableDef` to their `getSource()`, but does not normalize the incoming `ParentAvailableTable.availableTable` from the left tree. Therefore `Objects.equals(availableTable, existingDbView)` fails for extended views of the same base object and a new alias is created.
- Added a narrow Equinox weaving service for `com._1c.g5.v8.dt.qw.ui`:
  - replace private `SourcesEditProvider.equalsDbViewFromQueryNames(...)` with a helper that keeps the old temp-query comparison and additionally compares logical db-view identity after `ExtendedDbView* -> getSource()` normalization by system/Russian names;
  - inject a filter before every return of `QueryWizardAdoptSupport.collectObjectsToAdopt()`: remove candidates owned by another `IExtensionProject`, keep base configuration candidates unchanged.
- Registered the weaving service from the plugin activator and added `Eclipse-SupplementBundle: com._1c.g5.v8.dt.qw.ui` so woven QW classes can call `ContextLinksQueryWizardPatches`.

Follow-up for attempt 1:

- Build and redeploy of `v202606132148` succeeded.
- Fresh log confirmed:

```text
EDT Context Links Query Wizard weaving service registered
EDT Context Links Query Wizard weaving active for com._1c.g5.v8.dt.qw.ui
```

- Opening Query Wizard via `Ctrl+Alt+Q` from the embedded query editor worked after correcting screen coordinates to the second monitor.
- However no `patched Query Wizard ...` messages appeared. Likely cause: Equinox passed `preProcess(className, ...)` using a dot-qualified class name while the first transformer compared slash-qualified internal names.
- Adjusted the transformer to normalize `className.replace('.', '/')` before matching `SourcesEditProvider` and `QueryWizardAdoptSupport`.

Follow-up for attempt 1b:

- Build and redeploy of `v202606132154` succeeded.
- Opening Query Wizard after the class-name normalization produced the expected weaving logs:

```text
EDT Context Links Query Wizard weaving active for com._1c.g5.v8.dt.qw.ui
EDT Context Links patched Query Wizard table matching
```

- The first actual class patch then failed with:

```text
java.lang.TypeNotPresentException: Type com/_1c/g5/v8/dt/ql/model/QuerySchemaTotalControlPoint not present
Caused by: java.lang.ClassNotFoundException: com._1c.g5.v8.dt.ql.model.QuerySchemaTotalControlPoint cannot be found by org.objectweb.asm_9.6.0
```

- Root cause: `ClassWriter.COMPUTE_FRAMES` asks ASM to resolve QW/QL referenced classes through the ASM bundle classloader. That is wrong in Equinox weaving for EDT internals.
- Adjusted bytecode writing to use `ClassWriter.COMPUTE_MAXS` only and preserve existing frames.

Follow-up for attempt 1c:

- Build and redeploy of `v202606132156` succeeded.
- Fresh EDT log after opening Query Wizard confirmed:

```text
EDT Context Links Query Wizard weaving service registered
EDT Context Links Query Wizard weaving active for com._1c.g5.v8.dt.qw.ui
EDT Context Links patched Query Wizard table matching
```

- No `TypeNotPresentException`, `ClassFormatError`, `VerifyError`, or plugin-specific unhandled event-loop exception was observed after opening Query Wizard with the patched class writer.
- Manual Query Wizard check from the left **Database** tree:
  - expanded `—Ô‡‚Ó˜ÌËÍ_ÍÓÌÙË„Û‡ˆËË`;
  - selected fields `¬ÂÒËˇƒ‡ÌÌ˚ı`, `»ÏˇœÂ‰ÓÔÂ‰ÂÎÂÌÌ˚ıƒ‡ÌÌ˚ı`, ` Ó‰`, `Õ‡ËÏÂÌÓ‚‡ÌËÂ`;
  - middle **Tables** list contained exactly one `—Ô‡‚Ó˜ÌËÍ_ÍÓÌÙË„Û‡ˆËË`;
  - right **Fields** list contained all four selected fields using the same table alias.
- Screenshot evidence is in `target\screens\qw-after-keyboard-fields-2.png`.
- This confirms the duplicate-table alias defect is fixed for the tested `—Ô‡‚Ó˜ÌËÍ_ÍÓÌÙË„Û‡ˆËË` selection path.
- The foreign-extension adoption filter is implemented in bytecode, but the live `OK` scenario still needs a focused verification with `–‡Ò¯2_*` objects. The expected confirmation log is `EDT Context Links patched Query Wizard adoption filter`; it was not emitted during the table-alias verification because `QueryWizardAdoptSupport` was not loaded on that path.

Follow-up for attempt 1d:

- After commit `9c73a9e`, checked the still-open Query Wizard through Win32/UI Automation instead of coordinate clicks.
- Native dialog handle was found as `#32770` with title ` ÓÌÒÚÛÍÚÓ Á‡ÔÓÒ‡`; button handles were visible, including `OK`.
- Sending `BM_CLICK` to the native `OK` button worked and opened EDT's normal confirmation dialog:

```text
«‡ÔÓÒ ËÒÔÓÎ¸ÁÛÂÚ Ó·˙ÂÍÚ˚, ÓÚÒÛÚÒÚ‚Û˛˘ËÂ ‚ ‡Ò¯ËÂÌËË ÍÓÌÙË„Û‡ˆËË.
ƒÓ·‡‚ËÚ¸ ËÒÔÓÎ¸ÁÛÂÏ˚Â Ó·˙ÂÍÚ˚ ‚ ‡Ò¯ËÂÌËÂ ÍÓÌÙË„Û‡ˆËË?
```

- This proves the earlier "OK does not work" observation was caused by missed coordinates/focus, not by the current weaving patch.
- Fresh log confirmed the second woven class is loaded too:

```text
EDT Context Links patched Query Wizard adoption filter
```

- Pressed `ÕÂÚ`; the Query Wizard closed cleanly.
- Remaining live verification gap: reproduce a clean `–‡Ò¯2_*`-only selection and confirm that `filterObjectsToAdopt(...)` logs `QW adoption skip foreign extension object=...` and suppresses the confirmation prompt when all adoption candidates belong to linked extensions.

## 2026-06-14 - Application Infobase Update Skip Settings, Attempt 1

User requested the last feature for the Applications view:

- Add a context menu action for infobase applications: configure projects participating in infobase update.
- Allow skipping the base configuration project or selected extension projects when EDT checks/updates an infobase application.
- Skipped projects must not trigger the "configuration changed, update first?" prompt and must not block the remaining flow: launch and updates of non-skipped projects should continue.
- Also keep noisy plugin diagnostics quiet unless debug logging is explicitly enabled.

Implementation direction:

- Added persistent project setting on the application/base project: `disabledApplicationUpdateProjects`.
- Added menu command `Õ‡ÒÚÓËÚ¸ Ó·ÌÓ‚ÎˇÂÏ˚Â ÔÓÂÍÚ˚` into `popup:com.e1c.g5.dt.applications.ui.view`.
- The dialog lists the application project plus dependent extension projects; selected items are excluded from update/check flows.
- Added an OSGi wrapper for `IInfobaseSynchronizationManager` with service ranking 1000 and a wrapper marker, following the existing QL global-scope wrapper pattern.
- For disabled projects the wrapper softly answers:
  - `getEqualityState(...) -> EQUAL`;
  - `isConnected(...) -> true`;
  - `updateInfobase(...) / reloadInfobase(...) -> true`;
  - `retrieveInfobaseChanges(...) -> NO_CHANGES`;
  - `updateAllInfobases(...) -> empty map`.
- Extension projects are normalized back to their parent configuration project before reading the setting, so settings saved on the infobase application's base project also apply when EDT checks an extension project directly.
- Per-operation skip diagnostics use `ContextLinks.logDebug(...)`, so they are silent unless `-Dru.xelgo.edt.contextlinks.ui.debug=true` is enabled.

Build notes:

- First compile failed because `IInfobaseAccessSettings` is non-API and cannot be imported directly.
- Reworked the synchronization wrapper as a dynamic proxy/`InvocationHandler`; this avoids direct references to non-API method parameter types while still implementing `IInfobaseSynchronizationManager`.
- `mvn -q -DskipTests package` succeeded after the dynamic proxy rewrite.

Verification still needed after redeploy:

- Open Applications view, right-click an infobase application, confirm `Õ‡ÒÚÓËÚ¸ Ó·ÌÓ‚ÎˇÂÏ˚Â ÔÓÂÍÚ˚` is visible.
- Disable the base configuration and/or an extension project.
- Change a disabled project, then start/update the application and confirm EDT does not show the update prompt for that disabled project.
- Confirm non-disabled extensions still update normally.

## 2026-06-14 - Application Infobase Update Skip Settings, Attempt 2

- Corrected `tools/redeploy-edt-main.ps1` default workspace from `EDT DEV` to `EDTDEV` after confirming the active EDT workspace is `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDTDEV`.
- Removed noisy constructor/runtime binding diagnostics from Context Links BSL/scope wrappers. Kept meaningful registration logs and focused debug logs.
- Rebuilt and redeployed with `tools\redeploy-edt-main.ps1` to EDT workspace `EDTDEV`.
- Installed feature version: `1.0.0.v202606140742`.
- Workspace log check after restart contains:
  - `EDT Context Links Query Wizard weaving service registered`
  - `EDT Context Links QL BM global scope wrapper registered`
  - `EDT Context Links infobase update skip wrapper registered`
- No `ClassNotFoundException`, `NoClassDefFoundError`, `ClassCastException`, `Multiple services`, `IllegalStateException`, `Unhandled event loop exception`, or `BundleException` was found in the filtered startup log.
- Manual UI verification still required: Applications view context menu visibility and actual skip behavior on disabled update projects.

## 2026-06-14 - Application Update Skip Chain, Attempt 3

- Checked active workspace log: `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDTDEV\.metadata\.log`.
- Found persisted update skip setting on project ` ÓÌÙË„Û‡ˆËˇ`: `disabledApplicationUpdateProjects =  ÓÌÙË„Û‡ˆËˇ`.
- Root cause: previous proxy returned an empty result for `updateAllInfobases( ÓÌÙË„Û‡ˆËˇ, ...)`, which skipped the whole application update chain, including enabled extensions such as ` ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ`.
- Changed `updateAllInfobases` handling: when any project is disabled, the proxy reflects EDT's internal chain and skips only the disabled project while still traversing dependent extension projects.
- Fixed disabled `void` methods: `connectInfobase` and `reconnectIfConnected` now truly no-op for disabled projects instead of logging skip and then delegating.
- Fixed `tools\redeploy-edt-main.ps1` process detection for workspace `EDTDEV`: it now matches both Windows paths and `file:/C:/.../EDTDEV/` command-line forms, so the real EDT `javaw.exe` process is killed before redeploy.
- Made plugin debug logging opt-in via `-DebugPlugin`; regular redeploy no longer starts EDT with `-Dru.xelgo.edt.contextlinks.ui.debug=true`.
- Removed noisy warm-up/fallback logs from normal plugin logging.
- Rebuilt and redeployed to `EDTDEV`; installed feature version `1.0.0.v202606140816`.
- Post-start log check shows only plugin registrations and no `NoSuchMethodException`, `NoSuchFieldException`, `InaccessibleObjectException`, `ClassNotFoundException`, `NoClassDefFoundError`, `ClassCastException`, `BundleException`, or `Unhandled event loop exception` from the plugin.
- Manual UI validation still needed: with ` ÓÌÙË„Û‡ˆËˇ` disabled, ` ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ` should still update when the application update flow runs.

## 2026-06-14 - Disabled Application Update Conflict Prompt, Attempt 4

- Reproduced the remaining symptom from EDT log after a successful routed extension update: the next application update could still open EDT's "»ÁÏÂÌÂÌËˇ ÍÓÌÙË„Û‡ˆËË ËÌÙÓÏ‡ˆËÓÌÌÓÈ ·‡Á˚" dialog for ` ÓÌÙË„Û‡ˆËˇ`, listing extension-owned objects such as `Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ` and `Configuration.–‡Ò¯ËÂÌËÂ`.
- The conflict is not produced by the public `retrieveInfobaseChanges(...)` manager method; stack trace shows it comes from `AbstractInfobaseConnection.updateDatabase(...)` through `InfobaseUpdateDialogBasedCallback.resolveInfobaseChanges(...)` while EDT updates a routed extension.
- Kept the previous route fix: disabled base `updateInfobase(...)` is not delegated with a swapped project anymore. Instead the proxy gets the dependent extension's own `InfobaseSynchronization`, synchronizes its connections, and calls `updateConnectedInfobase(...)` on that synchronization.
- Added a wrapper around routed `IInfobaseUpdateCallback`:
  - `onConfirm(...)` and other callback methods still delegate to EDT;
  - `resolveInfobaseChanges(...)` returns `InfobaseConflictResolutionResult.IGNORED` for routed updates from a disabled application project;
  - debug log marker: `EDT Context Links DEBUG [application.update.conflict.skip] applicationProject=... routedProject=... conflictProject=...`.
- Rebuilt and redeployed to workspace `EDTDEV`; installed feature version `1.0.0.v202606140840`.
- Post-start log check shows plugin registrations and no `NoSuchMethodException`, `NoSuchFieldException`, `IllegalAccessException`, `ClassCastException`, `BundleException`, `Unhandled event loop exception`, or proxy stack traces.
- Manual UI validation still needed: repeat application update after a successful extension update and confirm the conflict dialog no longer appears; if it still appears, check for the `application.update.conflict.skip` marker to narrow the callback project/object set.

## 2026-06-14 - Reverse Synchronization Regression, Attempt 5

- User added `–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ2` through Configurator into one extension and EDT did not offer to import it back.
- Fresh log showed the previous suppression was too broad:
  - `application.update.conflict.skip applicationProject= ÓÌÙË„Û‡ˆËˇ routedProject= ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ2 conflictProject= ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ2`;
  - `application.update.conflict.skip applicationProject= ÓÌÙË„Û‡ˆËˇ routedProject= ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ conflictProject= ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ`.
- Root cause: the wrapped `IInfobaseUpdateCallback.resolveInfobaseChanges(...)` ignored conflicts for the routed extension project itself, hiding legitimate reverse-sync changes made in Configurator.
- Removed the callback suppression wrapper and restored EDT's normal conflict/import dialog behavior for routed extension projects.
- Kept the safer routed update logic that updates enabled dependent extensions through their own `InfobaseSynchronization` instead of invoking the public manager with a swapped `IProject`.
- Next investigation, if the stale post-update prompt remains: distinguish false echo conflicts from real Configurator edits using `IInfobaseConfigurationChange.getObjectChanges()` / `ObjectChange.getPlatformQualifiedName()` instead of suppressing all routed extension conflicts.
## 2026-06-14 - Routed Update Conflict Object Diagnostics, Attempt 6

- User confirmed that in EDT's import-changes dialog only `Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ2` is a real Configurator-side change; `Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ`, `Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ1`, and `Configuration.–‡Ò¯ËÂÌËÂ` look like stale echo changes after previous EDT-to-infobase extension update.
- Added a non-invasive wrapper around routed `IInfobaseUpdateCallback.resolveInfobaseChanges(...)` that only logs `IInfobaseConfigurationChange` details and then delegates to EDT normally.
- New debug marker: `EDT Context Links DEBUG [application.update.conflict.inspect] applicationProject=... routedProject=... conflictProject=... empty=... fullReload=... objectChanges=[TYPE:Platform.Name,...]`.
- Important: this attempt does not return `InfobaseConflictResolutionResult.IGNORED`; legitimate reverse synchronization, including `–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ2`, should still be offered by EDT.
- Rebuilt and redeployed to workspace `EDTDEV` with `-DebugPlugin`; installed feature version: `1.0.0.v202606140854`.
- Post-start log check shows plugin registrations and no `NoSuchMethodException`, `NoSuchFieldException`, `ClassCastException`, `NoClassDefFoundError`, `BundleException`, or `Unhandled event loop exception` from the plugin.
- Next step after reproducing the dialog: inspect `application.update.conflict.inspect` lines and use the object/type list to decide which changes are stale echoes and which are real Configurator imports.

## 2026-06-14 - Duplicate Extension Identity Finding

- The new `application.update.conflict.inspect` diagnostics fired during routed update of ` ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ2`.
- EDT reported the conflict as belonging to `conflictProject= ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ2`, but object changes were `NEW:Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ`, `NEW:Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ1`, `MODIFIED:Configuration.–‡Ò¯ËÂÌËÂ`, and `MODIFIED:CommonModule.–‡Ò¯2_ÃÓ‰ÛÎ¸.Module`.
- `Catalog.–‡Ò¯2_—Ô‡‚Ó˜ÌËÍ2` was not present in this logged conflict set, so the current prompt is not simply "all real changes plus noise"; it is comparing the wrong/stale extension content.
- Workspace inspection showed both extension projects have the same extension identity in `src/Configuration/Configuration.mdo`: `uuid="76d717b9-bdaa-4259-811a-57d7c3af5154"` and `<name>–‡Ò¯ËÂÌËÂ</name>`.
- The false `NEW` objects are present in project ` ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ`, but absent from ` ÓÌÙË„Û‡ˆËˇ.–‡Ò¯ËÂÌËÂ2`; because both projects use the same extension identity, the infobase synchronization can see objects from one extension project as changes for the other.
- This explains why objects previously uploaded from EDT are offered back as external changes: there are two workspace projects representing the same runtime extension identity.

## 2026-06-14 - Product Rename to EDT Extension Tweaks

- Chosen new public plugin name: `EDT Extension Tweaks`.
- Updated public display metadata: README, Maven project name, feature name/description, source feature name, p2 category label, plugin name, command category, and Java log prefix.
- Kept internal bundle/package/repository identifiers as `ru.xelgo.edt.contextlinks.*` to avoid a risky OSGi/package rename while the plugin is under active debugging.
- `mvn -q -DskipTests package` succeeded.

## 2026-06-14 - Large Workspace Build Hang Profiling

- Investigated workspace `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH` after EDT hung during a large project build with extensions.
- EDT process used Zulu Java 17 and reached roughly 30 GB working set; UI process was not responding.
- Captured a usable thread dump before EDT was closed: `diagnostics\EDT-UH-hang-20260614-220854\thread-dump-00.txt` (diagnostics are ignored by git).
- Thread dump confirmed the external performance warning: parallel `LCBuilderState-*` build threads were executing BSL/Xtext scope resolution through EDT Extension Tweaks wrappers.
- Hot/problematic stacks included:
  - `ContextLinks.getContextProjectNames(...)` -> `Resource.getPersistentProperty(...)` -> `PropertyManager2.getProperty(...)`, causing multiple parallel build threads to block on Eclipse workspace metadata property access.
  - `ContextLinksCachedScopeProvider.forgetModuleScope(...)` doing `ConcurrentHashMap.keySet().removeIf(...)` during module scope clearing.
- Added in-memory caching for context project names so build-time scope calls do not repeatedly read persistent project properties.
- Added an index from BSL block name to module scope keys, so clearing one module no longer scans all cached module scope keys.
- Added `tools/capture-edt-diagnostics.ps1` to capture workspace log, thread dumps, heap info, and JFR when possible while EDT is still hung.
- `mvn -q -DskipTests package` succeeded after the performance fix.

## 2026-06-14 - Large Workspace Build Hang, Attempt 2

- Redeployed `1.1.1` with the first performance fix to workspace `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`.
- User reported another hang during build.
- Workspace log showed repeated EDT resource manager warnings:
  - `CPU overload`;
  - `Sustained CPU overload`;
  - `Critical CPU overload`;
  - memory grew to roughly `8.57GB / 8.59GB` with `-Xmx8192m`.
- `jcmd GC.heap_info` succeeded once and confirmed G1 heap was almost full: `total 8388608K, used 8371290K`.
- `jcmd JFR.start`, `jcmd Thread.print -l`, and `jstack -l` did not complete even with longer timeouts, so the JVM was too busy or memory-starved to serve attach diagnostics reliably.
- Root suspicion after code review: plugin-level `moduleScopes` mirrored every BSL module/block scope and retained `IScope` object graphs outside EDT's own cache lifecycle. On a large configuration this can multiply retained model/scope objects and push the JVM into GC pressure.
- Removed the custom module-scope mirror entirely:
  - no `moduleScopes`;
  - no `moduleScopeVersions`;
  - no `moduleScopeKeysByBlock`;
  - no `addScope(...)`, `getScope(...)`, or `clearScopes(...)` overrides for BSL module scopes.
- Kept the lighter project-level linked scope composition for type-item/property scopes and the persistent-settings cache from the previous attempt.
- Updated `tools/redeploy-edt-main.ps1` to start EDT with a configurable heap and default `-Xmx20g` for heavy workspaces.
- `mvn -q -DskipTests package` succeeded after the change.
- Also fixed `tools/capture-edt-diagnostics.ps1` after the failed live capture:
  - process lookup now includes `1cedt.exe` in addition to `javaw.exe` / `java.exe`;
  - `jcmd.exe` is discovered from explicit `-JcmdPath`, `JAVA_HOME`, `C:\Program Files\1C\1CE\components\*jdk-full-17*`, or PATH instead of assuming it lives next to `1cedt.exe`.

## 2026-06-14 - Large Workspace Build Hang, Attempt 3

- User reported another hang after raising EDT startup heap to `-Xmx20g` and removing the custom BSL module-scope mirror.
- Live diagnostics for process `54560` in workspace `EDT UH` showed the heap was again almost full:
  - before: `total 20971520K, used 20885136K`;
  - after: `total 20971520K, used 20856326K`.
- `Thread.print`, `GC.class_histogram`, and repeated attach diagnostics still timed out, so the JVM was again memory/CPU starved enough that attach was unreliable.
- Workspace log showed contexts started successfully, then memory climbed during later BSL work; one EDT-side stack was `ExportMethodTypeProvider.updateExportMethodIndex(...)` with `Transaction is not active`, followed by repeated `Critical CPU overload`.
- Code review found another likely multiplier: plugin scope wrappers materialized complete `IEObjectDescription` collections into `ArrayList` in `getAllElements()` and several `getElements(...)` methods.
- Reworked scope wrappers to return lazy iterables instead of materializing all descriptions:
  - `ContextLinksProjectScope` now concatenates own and linked scopes lazily;
  - `CurrentFirstLinkedScope.getAllElements()` now performs lazy concatenation and lazy deduplication by `QualifiedName`;
  - `CurrentProjectFriendlyScope.wrap(...)` now wraps descriptions lazily.
- Fixed `tools/capture-edt-diagnostics.ps1` for Windows PowerShell by replacing `ProcessStartInfo.ArgumentList` with quoted `Arguments` string construction.
- `mvn -q -DskipTests package` succeeded.

## 2026-06-14 - Disable Context Tweaks During Build, Attempt 4

- Added a build/indexing guard for scope-extension paths. By default `ru.xelgo.edt.contextlinks.ui.disableDuringBuild=true`.
- When a scope request is detected from build/indexing/resource-description/export-method stacks, the plugin returns EDT's own scope/container/context without adding linked projects.
- Guarded paths:
  - BSL cached type-item/property scope composition;
  - BSL visible containers;
  - QL/BM global scope composition;
  - common module contextDef fallback generation.
- Removed startup warm-up builds. `ContextLinksStartup` now only registers the QL global scope wrapper and logs that warm-up builds are disabled; it no longer schedules `WorkspaceJob` and no longer calls `project.build(...)`.
- Added throttled INFO diagnostics for build investigation, visible without debug flag:
  - `EDT Extension Tweaks [build.skip] ...` when plugin behavior is skipped because the call came from a build/indexing stack;
  - `EDT Extension Tweaks [scope.extend] ...` when the plugin actually extends a scope/container/context outside a detected build path.
- `mvn -q -DskipTests package` succeeded.

## 2026-06-14 Attempt 5 - skip BSL context extensions on background threads

Observation from EDT UH after build-time skip guards:
- Startup warm-up is disabled and explicit build stack guards are firing (`[build.skip]`).
- The hang still reaches critical CPU/heap pressure near 20g.
- Logs still contain `[scope.extend]` for BSL features from background workers such as `Worker-*`, `ForkJoinPool-*`, `derived_data_executor_*` and Xtext/DD jobs.

Change:
- Treat BSL/module context features as build-sensitive on generic background threads too.
- Skip `bsl-*` and `module-context-*` context extension on `Worker-*`, `ForkJoinPool-*`, `derived_data_executor_*`, `LCBuilderState-*`, Xtext/reconcile/check threads and `AEF 2.0 Thread-*`.
- Leave query constructor / QL / BM paths untouched so the interactive query console context should still work.

Expected result:
- During large project builds our plugin should no longer inject linked extension scopes into background BSL/DD calculations.
- Logs should show `[build.skip] ... frame=background-thread` instead of `[scope.extend]` for those background BSL paths.

## 2026-06-14 Attempt 6 - remove retained BSL scope cache

Observation after Attempt 5 on EDT UH:
- Installed build `1.1.1.v202606141950`.
- `scope.extend count` stayed at 0 during the failing run, so linked context was no longer injected into background build/DD threads.
- Build still reached heap saturation and UI stopped responding.
- Heap info before diagnostics: G1 heap total 20971520K, used 20872590K.
- `jcmd Thread.print` and JFR start timed out once the heap was almost full.
- Workspace log shows EDT BSL validation exception (`StringIndexOutOfBoundsException` in `BslJavaValidator.checkStringLiteral`) followed by CPU/heap overload.

Hypothesis:
- `ContextLinksCachedScopeProvider.stableProjectScopes` may retain huge BSL `IScope` instances for large configuration/extension projects even when context extension is skipped.
- On a large workspace this can prevent EDT from releasing builder/DD scope structures after cache clears.

Change:
- Removed `stableProjectScopes` and all remembered direct scope fallback logic.
- Linked context now uses only EDT's current direct scope from `super.getTypeItemScope` / `super.getPropertyScope`.

Expected result:
- The plugin should no longer keep strong references to large BSL scope graphs.
- Query-console context may depend more strictly on EDT's own initialized scopes, but build memory pressure should drop.

## 2026-06-15 Attempt 7 - remove BSL runtime module registration

Observation after Attempt 6 on EDT UH:
- Correct workspace was relaunched with quoted `-data "C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH"`.
- Installed bundle was `1.1.1.v202606142001`.
- `stableProjectScopes` was removed, but BSL extension still appeared during startup/build-sensitive paths:
  - `scope.extend count: 3`
  - examples: `ForkJoinPool.commonPool-worker-13` and `main` while external object project context was being initialized.
- EDT still logged `StringIndexOutOfBoundsException` in `BslJavaValidator.checkStringLiteral` and CPU overload messages.

Conclusion:
- Thread-name/build-stack guards are not enough. The BSL runtime module itself is still too broad for large workspace builds.

Change:
- Removed the `com._1c.g5.v8.dt.bsl.bslRuntimeModuleExtension` registration from `plugin.xml`.
- Query Wizard weaving, QL/BM global scope wrapper, application update controls and UI handlers remain registered outside this BSL runtime module.

Expected result:
- EDT BSL build/validation should use native EDT services only.
- The plugin should no longer emit BSL `scope.extend` or `build.skip` lines during workspace build.
- Query constructor context fixes should remain active through the non-BSL query/QL integration points.

## 2026-06-15 Attempt 8 - restore BSL content assist only

Observation after Attempt 7:
- Large workspace build passed with `1.1.1.v202606142014`.
- Logs no longer showed BSL `scope.extend` or `build.skip`, confirming the BSL runtime module was fully detached.
- User reported that context completion stopped working completely.

Conclusion:
- Removing the whole BSL runtime module is too broad: it protects build, but also removes the context-completion bridge.

Change:
- Restored `com._1c.g5.v8.dt.bsl.bslRuntimeModuleExtension` registration.
- Removed only the `IContainer.Manager` binding from `ContextLinksBslRuntimeModule`; Xtext container visibility is left native to EDT.
- Added `ContextLinks.shouldSkipBslContextExtension(feature)`.
- BSL linked scope/fallback logic now runs only when the stack looks like interactive content assist/proposal/completion.
- Non-interactive BSL calls are skipped and logged as `[build.skip] ... frame=non-interactive-bsl`.

Expected result:
- Content assist should see linked extension context again.
- Workspace startup/build/DD should not inject linked BSL scopes unless invoked from an actual content-assist stack.

## 2026-06-15 Attempt 9 - allow ContentAssist worker threads

Observation after Attempt 8:
- BSL runtime module was restored and build/startup stayed stable: no `scope.extend`, no overload.
- Logs showed many skipped calls from `Worker-*: ContentAssist resource sync`.
- This can explain why user-facing context completion still has no linked context: EDT performs part of content assist preparation on background worker threads.

Change:
- `shouldSkipBslContextExtension` now allows interactive assist before applying the generic background/build guard.
- `isInteractiveBslAssistRequest` also checks the thread name for `contentassist`, `proposal`, or `completion` markers.

Expected result:
- Background content-assist sync can use linked BSL context.
- Build/DD/reconciler paths without content-assist markers remain skipped.

## 2026-06-15 Attempt 10 - restore containers for content assist

Observation after Attempt 9:
- User reported context completion is still unavailable in the external data processor.
- Fresh logs show `ContentAssist resource sync` now reaches `scope.extend` for `bsl-cache-property` and `bsl-cache-type-item`.
- There are no `bsl-containers` scope extensions because Attempt 8 removed the `IContainer.Manager` binding.

Hypothesis:
- For external object projects, linked BSL cache scopes alone are not enough; Xtext also needs linked resource containers visible during content assist.

Change:
- Restored `IContainer.Manager -> ContextLinksContainerManager` binding.
- Changed `ContextLinksContainerManager` to use `shouldSkipBslContextExtension("bsl-containers")` so linked containers are added only for content-assist/proposal/completion paths.

Expected result:
- External data processor content assist should see linked configuration/extension resources again.
- Build/DD/reconciler paths should continue to receive standard EDT containers only.

## 2026-06-15 Attempt 11 - allow short content-assist continuation window

Observation after Attempt 10:
- User still reports dead context completion in the external data processor.
- Logs show content assist does enter the plugin and extends `bsl-containers`, `bsl-cache-property`, and `bsl-cache-type-item` for `–í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞`.
- Immediately after that, EDT schedules proposal/type work on `ForkJoinPool-*`; those calls are skipped as `non-interactive-bsl` or `background-thread`.

Hypothesis:
- EDT content assist has a multi-stage pipeline: `Worker-*: ContentAssist resource sync` warms resources, then `ForkJoinPool-*` computes proposals/types.
- Allowing only the first stage leaves actual proposal computation without linked scopes.

Change:
- Added a short per-project BSL assist window (`15s`) in `ContextLinks`.
- When a project enters an interactive content-assist path, remember it.
- During that window, allow linked BSL scopes on `ForkJoinPool-*` continuations for the same project.
- Pass project information into BSL guards from cache provider, container manager, and module context fallback.

Expected result:
- External data processor content assist should keep linked context through the whole proposal pipeline.
- Normal build/DD/reconciler paths remain skipped outside the short assist continuation window.

## 2026-06-15 Attempt 12 - keep BSL assist continuation when EDT drops project context

Observation after Attempt 11:
- User reports external data processor content assist is still dead.
- Logs show the plugin enters `Worker-*: ContentAssist resource sync` and extends `bsl-containers`, `bsl-cache-property`, and `bsl-cache-type-item` for `–í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞`.
- Immediately after that, EDT still invokes `bsl-cache-type-item` on `ForkJoinPool.commonPool-*`, and the guard skips it as `non-interactive-bsl`.

Hypothesis:
- A later proposal/type stage can run without the original project context, so the per-project continuation window is not enough.

Change:
- Added a short global BSL assist timestamp opened only by a real interactive content-assist/proposal/completion call.
- `ForkJoinPool-*` and `ForkJoinPool.commonPool-*` continuations can now use linked BSL scopes during that short window even if EDT does not pass the project through.

Expected result:
- The proposal pipeline should keep linked BSL context through project-less continuation calls.
- Build/DD/reconciler paths remain skipped unless they happen inside the short window immediately after an actual content-assist request.

## 2026-06-15 Deployment script fix - EDT UH workspace

Problem:
- Manual redeploy snippets repeatedly risked starting EDT with an incorrectly quoted `-data` argument or with the wrong workspace.
- The existing `tools/redeploy-edt-main.ps1` still defaulted to `EDTDEV` and used the slower p2 director flow by default.

Change:
- Changed the default workspace to `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`.
- Added a fast local install path as the default: copy the freshly built bundle jar to `.p2\pool\plugins` and sync `bundles.info` with UTF-8 without BOM.
- Kept p2 director available behind `-UseP2Director`.
- Added `-ForceKill` behavior that kills existing EDT/EDT console processes before installing and launching.

Verification:
- Ran `tools\redeploy-edt-main.ps1 -SkipBuild -ForceKill`.
- EDT started with a single `1cedt.exe` process and the command line contains `-data "C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH"`.
- `bundles.info` points to `ru.xelgo.edt.contextlinks.ui_1.1.1.v202606142040.jar`.
- Workspace log shows EDT Extension Tweaks services registered.

## 2026-06-15 Research - v1.1.1 vs current BSL content assist regression

Goal:
- Stop iterating blindly on external object BSL content assist.
- Compare the release build that mostly worked for context completion (`v1.1.1`, commit `c64dfbc`) with the current build (`c9736ab`).

Baseline:
- `v1.1.1` keeps the BSL runtime module registration unchanged:
  - `BslCachedScopeProvider -> ContextLinksCachedScopeProvider`
  - `IScopeProvider -> ContextLinksBslScopeProvider`
  - `IBslModuleContextDefService -> ContextLinksModuleContextDefService`
  - `IContainer.Manager -> ContextLinksContainerManager`
- Current `HEAD` has the same runtime-module binding surface. The regression is not caused by missing extension registration.

Major behavior removed after `v1.1.1`:
- Startup warm-up builds were removed from `ContextLinksStartup`.
  - Release behavior: schedule two delayed workspace warm-up passes and call `project.build(FULL_BUILD, ...)` for linked extension projects.
  - Current behavior: only registers wrappers and logs `startup warm-up builds are disabled`.
- `ContextLinksCachedScopeProvider` lost the release-era BSL cache mirrors:
  - `stableProjectScopes` for last-known-good project type/property scopes;
  - `moduleScopes`, `moduleScopeVersions`, and `projectScopeVersions` for BSL block/module scope fallback;
  - overrides for `addScope(...)`, `getScope(...)`, and `clearScopes(...)`.
- The current provider uses only EDT's current direct scope from `super.getTypeItemScope(...)` / `super.getPropertyScope(...)` and lazy linked scope composition.

Why those removals made sense for build stability:
- The original EDT UH hang dump showed build threads inside plugin BSL wrappers:
  - `ContextLinksCachedScopeProvider.forgetModuleScope(...)` scanning retained module scope keys;
  - `ContextLinks.getContextProjectNames(...)` reading Eclipse persistent properties through `PropertyManager2` from parallel `LCBuilderState-*` threads.
- Later diagnostics showed heap/CPU overload, so retaining full BSL `IScope` graphs globally was a plausible memory multiplier.

Why the same removals likely broke content assist:
- Older research in this log already identified the core issue: during rebuild EDT can temporarily return `null` or incomplete direct scopes for a linked producer project. `stableProjectScopes` was introduced exactly to keep consumer projects seeing the last-known-good linked context during that gap.
- Current logs prove the plugin is invoked by content assist and links `–í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞ -> cf.–ú–∞–≥–Ω–∏—Ç–ú–∞—Ä–∫–µ—Ç`:
  - `scope.extend feature=bsl-containers ... ContentAssist resource sync ... added=[cf.–ú–∞–≥–Ω–∏—Ç–ú–∞—Ä–∫–µ—Ç=cf.–ú–∞–≥–Ω–∏—Ç–ú–∞—Ä–∫–µ—Ç]`
  - `scope.extend feature=bsl-cache-property ... linked=[cf.–ú–∞–≥–Ω–∏—Ç–ú–∞—Ä–∫–µ—Ç]`
  - `scope.extend feature=bsl-cache-type-item ... linked=[cf.–ú–∞–≥–Ω–∏—Ç–ú–∞—Ä–∫–µ—Ç]`
- But that only proves a linked `IScope` object exists. It does not prove the linked scope has useful BSL elements. The old warm-up/mirror system likely populated or preserved those elements.
- Current logs show `module-context-fallback` never extends during interactive content assist; it is only skipped on background/reconciler/build threads. In the release build, fallback context could be created during warm-up/background indexing and then reused.

Current conclusion:
- The safe build direction is still correct: do not let linked BSL context participate in full workspace build/DD/indexing.
- The current implementation overcorrected by removing all release-era fallback state. The next fix should restore only the interactive/content-assist part of the release mechanism, not the build-time/global retention.

Next proposed experiment:
- Do not restore startup full-build warm-up yet.
- Reintroduce a bounded, assist-only last-known-good cache for BSL project scopes:
  - update it only while `shouldSkipBslContextExtension(...)` would allow an interactive content-assist request/continuation;
  - never update it from `LCBuilderState-*`, `derived_data_executor_*`, Xtext reconciler/check/build threads;
  - keep a short TTL and/or small per-project map so large scope graphs are not retained indefinitely.
- Consider reintroducing module scope fallback only behind the same assist-only gate if project scope fallback is not enough.
- Add one low-noise diagnostic first: for content assist only, sample the first few names from linked type/property scopes to confirm whether the current linked scopes are empty or populated.

## 2026-06-15 Release v1.1.1 profiling - inactive external object context

Observation:
- While running release code `v1.1.1` in workspace `EDT UH`, the user hit an EDT dialog:
  `–ö–æ–Ω—Ç–µ–∫—Å—Ç –ø—Ä–æ–µ–∫—Ç–∞ –Ω–µ –≥–æ—Ç–æ–≤` / `–ü—Ä–æ–µ–∫—Ç –í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞ –µ—â–µ –Ω–µ —Å—Ç–∞—Ä—Ç–æ–≤–∞–ª, –ª–∏–±–æ —É–∂–µ –∑–∞–∫—Ä—ã—Ç`.
- Workspace log shows the project lifecycle around the incident:
  - `Project context is being stopped: –í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞ (CLEAN)`;
  - `Project context is stopped: –í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞`;
  - later `Project context is being started: –í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞ (CLEAN_IMPORT)` and `Project context is started: –í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞`.
- During the stopped/inactive window, EDT BSL editor/validation still tried to read the external data processor form context.

Relevant stack:
- `BmNamespaceInactiveException: The namespace '–í–Ω–µ—à–Ω—è—è–û–±—Ä–∞–±–æ—Ç–∫–∞' is not active`
- `FormImpl.getFormContext(...)`
- `BslModuleContextDefExtensionForm.getContextDef(...)`
- `ru.xelgo.edt.contextlinks.core.ContextLinksModuleContextDefService.getContextDef(...)`
- `BslScopeProvider.addContextPropertiesScopeWithoutEnvs(...)`
- `BslDerivedStateComputer.installDerivedState(...)`
- `BslNotifyingResourceValidator.validate(...)`
- `ValidationJob.run(...)`

Conclusion:
- This is another release-build failure mode, separate from pure heap pressure.
- The release plugin allows BSL context-def/scope integration while EDT is stopping or restarting project BM namespaces during clean/rebuild.
- The correct fixed build must not call into linked/current project BSL context services while a project namespace is inactive or while EDT is in CLEAN/CLEAN_IMPORT/build lifecycle.
- This supports the direction of gating BSL integration away from build/DD/project lifecycle operations while preserving it only for interactive content assist.

## 2026-06-15 Diagnostics default duration adjustment

Observation:
- The current EDT UH hang is already visible after roughly 6 minutes of build time.
- The long watcher run timed out after collecting enough evidence and did not add useful data after the JVM stopped responding to jcmd.

Change:
- tools/capture-edt-diagnostics.ps1 now defaults to workspace EDT UH.
- The default capture duration is now 360 seconds.

Next profiling run:
- Start capture before triggering the problematic rebuild.
- Prefer duration=360s and collect partial evidence earlier, because late attach commands may time out once the JVM is memory/CPU starved.

## 2026-06-15 Diagnostics default duration reduced to 4 minutes

Observation:
- The EDT UH build hang becomes visible quickly enough that a 6 minute capture is still longer than necessary.

Change:
- tools/capture-edt-diagnostics.ps1 now defaults to 240 seconds.

Next profiling run:
- Restart EDT with ForceKill, start the 240 second capture, then trigger or observe the build phase before the JVM becomes unresponsive.

## 2026-06-15 EDT launcher VM fix for profiling

Observation:
- After restart, EDT UH ran as 1cedt.exe without a separate attachable javaw.exe process.
- jcmd listed only itself, so JFR capture could not attach to the EDT process.

Change:
- tools/redeploy-edt-main.ps1 now passes -vm <LauncherVm> before -vmargs when starting EDT.

Expected result:
- EDT should start through the explicit JDK javaw.exe and become visible to jcmd for 240 second JFR captures.

## 2026-06-15 Capture script JFR start fix

Observation:
- The 240 second capture against EDT UH PID 2792 reproduced the hang, but JFR did not start.
- jcmd reported: Could not start recording, delay must be at least 1 second.
- Thread.print timed out from the first sample and repeatedly consumed capture time while the JVM was already overloaded.
- Workspace log still captured the failure pattern: DD waits, CPU overload, memory close to the 20g heap ceiling, and marker duplicate diagnostics.

Change:
- JFR.start now uses delay=1s and dumponexit=true.
- Repeated Thread.print sampling stops after two consecutive jcmd failures, leaving the JFR duration window intact.

Hypothesis update:
- User observation about duplicated exported procedures matches the marker duplicate diagnostics and the earlier duplicate table/context symptoms.
- The plugin likely allows the same linked extension context/scope to be registered or merged more than once during rebuild/restart lifecycle.
