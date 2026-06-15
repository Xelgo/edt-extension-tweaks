# Build Freeze And Singleton Investigation

This note replaces the mixed `DEBUG_LOG.md` sections about the heavy EDT workspace build hang, profiling, and the final Guice singleton fix.

## Goal

Stop EDT from freezing or overloading CPU during large project builds while preserving extension context functionality.

Main scenario:

- Workspace: `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`
- Heavy configuration with extensions.
- Build often froze around `Сборка cf: Обновление описания ресурсов`, near 33%.
- Memory usage was high but expected for EDT; the bad symptom was CPU overload and non-progressing build.

## Final Root Cause

The root cause was a lifecycle mismatch in BSL service replacements.

Native EDT services involved in BSL scope construction are Guice singletons:

- `com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider`
- `com._1c.g5.v8.dt.bsl.scoping.BslScopeProvider`
- `com._1c.g5.v8.dt.internal.bsl.contextdef.BslModuleContextDefService`

The plugin replaced these services with subclasses/implementations but initially did not copy:

```java
@com.google.inject.Singleton
```

Without `@Singleton`, Guice could create multiple service instances. For services with internal caches this can mean:

- repeated scope/model traversal;
- independent cache sets;
- duplicated exported methods/procedures in the model;
- increased CPU pressure during full builds;
- unstable context visibility.

Fixed by adding `@Singleton` to:

- `ContextLinksCachedScopeProvider`
- `ContextLinksBslScopeProvider`
- `ContextLinksModuleContextDefService`

Released as:

- GitHub release: `v1.1.2`
- P2 plugin version: `1.1.2.v202606152150`
- Update site:
  `jar:https://github.com/Xelgo/edt-extension-tweaks/releases/latest/download/edt-extension-tweaks-update-site.zip!/`

## How The Root Cause Was Confirmed

The decisive comparison was done with `javap -v` against EDT's native classes.

Useful command shape:

```powershell
javap -classpath C:\Users\USER\.p2\pool\plugins\com._1c.g5.v8.dt.bsl_28.0.1.v202605050943.jar -v com._1c.g5.v8.dt.bsl.scoping.BslCachedScopeProvider
```

The classfile tail showed:

```text
RuntimeVisibleAnnotations:
  com.google.inject.Singleton
```

The same check showed `@Singleton` on EDT's `BslScopeProvider` and `BslModuleContextDefService`.

`BslLightStateBasedContainerManager` did not have `@Singleton`, so `ContextLinksContainerManager` was intentionally not annotated.

## Profiling And Diagnostics Timeline

### Initial Large Workspace Hang

Observed:

- EDT hung during heavy build.
- Thread dumps showed parallel `LCBuilderState-*` build threads in BSL/Xtext scope/resource-description code.
- Some stacks crossed EDT Extension Tweaks wrappers, but no single obvious long-running plugin method explained the whole hang.

Early suspected hotspots:

- Reading persistent project properties from build threads.
- Clearing module scope maps by scanning all keys.
- Retaining or composing too many linked scopes.

Partial mitigations tried:

- In-memory context project-name cache.
- Faster module-scope key indexes.
- Reduced logging.
- Build-thread guards.
- Background-thread guards.
- Clearing linked caches before/after build.

None of those fully solved the heavy build freeze.

### JFR / Thread Dump Work

JFR captures and thread dumps helped establish the broad area:

- EDT/Xtext BSL resource description update was the hot zone.
- Build threads were working around `BslResource`, `BslResourceDescription`, `ExportMethodProvider`, derived data, and scope computation.
- Some captures were incomplete because the process could hang too hard to stop cleanly.

Useful lesson:

- A missing lifecycle annotation can look like an algorithmic performance bug because it multiplies service instances and caches.
- Before profiling deeper, compare lifecycle annotations of original and replacement services.

### Failed Architecture Detours

Several defensive designs were explored:

- Disable context tweaks during any build.
- Resume context after build.
- Make plugin inactive by default.
- Add manual activation.
- Remove the whole BSL runtime module.
- Keep only container manager.
- Keep only module context service.
- Keep only query-constructor registrations.

Important observations:

- Removing the whole BSL runtime module allowed the heavy build to progress.
- Removing the cached-scope binding broke BSL context in external object modules.
- Query Constructor could work without the full BSL runtime path, because it has its own QW/QL/BM hook.
- Full build suppression was too blunt; it made context flicker or disappear after builds.

### Duplicate Procedure Clue

After failed builds/restarts, EDT sometimes reported exported procedures as already defined even when the module contained a single declaration.

Interpretation at the time:

- Scope/model data was likely being built more than once or merged from multiple service instances.

This clue later aligned with the missing `@Singleton` root cause.

## Release Hygiene Rule

Whenever replacing or subclassing an EDT/Guice/Xtext service:

1. Find the native EDT class.
2. Run `javap -v` on the native class.
3. Check class-level annotations, especially:
   - `@com.google.inject.Singleton`
   - Xtext singleton binding annotations
   - injection/lifecycle annotations
4. Preserve the lifecycle on the replacement class unless there is a deliberate reason not to.
5. Only then start deeper profiling.

This rule exists because missing `@Singleton` cost roughly eight hours of debugging and produced misleading build/performance symptoms.

## Verification Checklist

- Build p2 update site with Maven.
- Verify bytecode annotations in the built plugin jar:

```powershell
javap -classpath <plugin.jar> -v ru.xelgo.edt.contextlinks.core.ContextLinksCachedScopeProvider
javap -classpath <plugin.jar> -v ru.xelgo.edt.contextlinks.core.ContextLinksBslScopeProvider
javap -classpath <plugin.jar> -v ru.xelgo.edt.contextlinks.core.ContextLinksModuleContextDefService
```

- Confirm each class has `RuntimeVisibleAnnotations: com.google.inject.Singleton`.
- Run heavy workspace build.
- Confirm Query Constructor still works.
- Confirm external object BSL context still resolves linked common-module methods.
