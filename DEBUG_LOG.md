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
