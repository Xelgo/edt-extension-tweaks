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
