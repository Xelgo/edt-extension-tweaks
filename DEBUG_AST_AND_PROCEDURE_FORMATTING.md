# AST Model And Procedure Formatting Investigation

This file is the new investigation log for building/editing BSL AST models and formatting procedure/function text.

It starts intentionally empty of historical conclusions because this is a new topic, separate from extension context, Query Constructor context, and build-freeze debugging.

## Goal

Understand how to safely generate, modify, format, and insert BSL procedure/function text using EDT/Xtext models instead of fragile string concatenation.

Potential scenarios:

- Build a BSL AST model for a generated procedure/function.
- Insert or replace procedure text in a module.
- Format generated code according to EDT/BSL formatter rules.
- Preserve existing comments, regions, annotations, directives, and line endings.
- Avoid breaking EDT derived state or validation caches.

## Working Hypotheses

- Prefer EDT/Xtext parser and formatter APIs over manual string templates where possible.
- Treat text generation and semantic model update as separate concerns:
  - parse/build valid BSL;
  - format;
  - insert into document/model;
  - let EDT reconcile.
- Do not mutate donor extension resources during generation for a consumer project.

## Questions To Answer

- Which EDT bundle owns the BSL parser and formatter APIs?
- Can we parse a standalone procedure/function fragment, or must we wrap it into a module?
- Which API returns a formatted text edit versus directly mutating an Xtext document?
- How does EDT preserve Russian keywords, comments, preprocessor directives, and regions?
- What is the safest way to apply generated text inside an open editor?
- What background jobs are triggered after applying generated BSL text?

## Test Matrix Draft

- Empty procedure without parameters.
- Procedure with export flag.
- Function with return value and default parameters.
- Server/client annotations.
- Preprocessor directives.
- Region/endregion blocks.
- Comments before and inside procedure.
- Existing module with same procedure name.
- Existing module with syntax errors nearby.
- External data processor module.
- Extension common module.

## Logging Rules

- Record exact EDT bundle/class names used for parser/formatter APIs.
- Record minimal input and output snippets.
- Record whether operation happened on text document, EMF model, or both.
- Record whether EDT validation markers changed after reconcile.
- Keep logs small and curated; do not paste full generated modules unless the full text is the actual failure case.

## 2026-06-16 - ChangeAndValidate Insert Formatting

### Reproduction

Real file:

`C:\Users\USER\git\uh_main\cf.МагнитМаркет\src\CommonModules\АвтоматическоеПолучениеЭДОКлиентСервер\Module.bsl`

Input shape:

```bsl
&ИзменениеИКонтроль("КлючРезультатаПериодическойОтправкиДанных")
Функция ММ_КлючРезультатаПериодическойОтправкиДанных() Экспорт
	#Вставка
	Если 1=1 Тогда
						 Тест = 1;
	КонецЕсли;
	#КонецВставки
	Возврат ПолноеИмяПодсистемы();
КонецФункции
```

Expected behavior: keep the original copied method text untouched, but format the extension-owned code inside `#Вставка` / `#КонецВставки`.

### EDT Formatter Finding

Bundle:

`com.e1c.g5.v8.dt.formatter.bsl_1.1.200.v202605050943.jar`

Class:

`com.e1c.g5.v8.dt.formatter.bsl.BslFormatter2`

Relevant bytecode result:

- `formatMethod(Method, IFormattableDocument)` calls private `hasChangeAndValidatePragma(Method)`.
- If the method has `Symbols.CHANGE_AND_VALIDATE_ANNOTATION_SYMBOLS`, `formatMethod` immediately returns.
- Because of that early return, the standard formatter never reaches `formatStatements(...)` for the method body.

This explains why the whole `&ИзменениеИКонтроль` method is skipped.

### BSL AST Finding

Bundle:

`com._1c.g5.v8.dt.bsl_28.0.1.v202605050943.jar`

Grammar:

`com/_1c/g5/v8/dt/bsl/Bsl.xtext`

Important grammar nodes:

- `PreprocessorStatementInsert returns Statement`
- `PreprocessorMethodStatementInsert returns RegionPreprocessor`
- `PreprocessorMethodInsert returns DeclareStatement`
- `InsertPreprocessorBslExpression returns RegionPreprocessorExpression`

Model bundle:

`com._1c.g5.v8.dt.bsl.model_12.0.0.v202605050943.jar`

Important model classes:

- `RegionPreprocessor`
- `RegionPreprocessorStatement`
- `RegionPreprocessorDeclareStatement`
- `RegionPreprocessorExpression`
- `PreprocessorItem`
- `RegionPreprocessorType.INSERT`

`RegionPreprocessor` has:

- `getItem()` - content inside the preprocessor block.
- inherited `getItemAfter()` from `Preprocessor` - content after the closing preprocessor marker.
- `computeType()` - returns `REGION`, `INSERT`, or `DELETE`.

### Implementation Decision

Do not call standard `formatRegionPreprocessor(...)` for `&ИзменениеИКонтроль`, because it formats both `getItem()` and `getItemAfter()`. In the target scenario `getItemAfter()` can contain original copied method text after `#КонецВставки`; formatting it would violate EDT's safety reason for skipping ChangeAndValidate methods.

Implemented a narrow formatter subclass:

`ru.xelgo.edt.contextlinks.core.ContextLinksBslFormatter`

Runtime binding:

`ContextLinksBslRuntimeModule.bindIFormatter2() -> ContextLinksBslFormatter`

Behavior:

- For ordinary methods: delegate to `super.formatMethod(...)`.
- For `&ИзменениеИКонтроль` / `&ChangeAndValidate` methods:
  - find nested `RegionPreprocessor` nodes;
  - keep only `computeType() == RegionPreprocessorType.INSERT`;
  - format only `region.getItem()`;
  - derive the base indentation from the actual line containing `#Вставка`;
  - replace whitespace after `#Вставка` with `newline + baseIndent + oneIndentUnit`;
  - replace whitespace before `#КонецВставки` with `newline + baseIndent`;
  - update the formatter indentation context for inserted content, so nested inserted statements are formatted relative to the insertion body;
  - keep `#Вставка` / `#КонецВставки` on the current code-block indentation level and indent only insertion content one level deeper;
  - do not format `region.getItemAfter()`.

Why this is needed:

- The standard EDT formatter intentionally skips the whole `&ИзменениеИКонтроль` method.
- The plugin also must not format the whole method, because that would touch original copied code after `#КонецВставки`.
- Therefore standard outer indentation contexts for `Процедура`/`Функция`, `Если`, `Цикл`, etc. are not created.
- A plain `document.interior(#Вставка, #КонецВставки, indent)` starts from zero indentation and produces body/end indentation that is too shallow.

Failed attempt:

- Tried a custom `ITextReplacer` that directly replaced hidden regions after `#Вставка` and before `#КонецВставки`.
- User check: formatting became worse / broke the text.
- Rolled this attempt back to the conservative formatter-API-only implementation.
- Do not reintroduce direct hidden-region replacement without an isolated formatter test harness and rendered text replacement inspection.

### Verification

Command:

```powershell
C:\Users\USER\Documents\EDT Plugins\.tools\apache-maven-3.9.9\bin\mvn.cmd -q -DskipTests package
```

Result: build passed.

### Runtime Check

First EDT UI test failed on `Ctrl+Shift+F` with:

```text
java.lang.NoClassDefFoundError: org/eclipse/xtext/xbase/lib/Procedures$Procedure1
	at ru.xelgo.edt.contextlinks.core.ContextLinksBslFormatter.normalizeAfterBeginInsert(...)
```

Conclusion:

- The custom formatter was actually selected by EDT.
- The formatter bundle lacked runtime visibility of `org.eclipse.xtext.xbase.lib`.

Fix:

- Added `org.eclipse.xtext.xbase.lib;version="[2.12.0,3.0.0)"` to `Import-Package`.

### Runtime Log After Import Fix

Workspace:

`C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`

The previous formatter dependency error disappeared from `.metadata\.log`.

New unrelated errors observed in the same session:

- `org.eclipse.xtext.builder.impl.XtextBuilder - Cannot open more than one transaction in one thread`
  - Root stack goes through `ModelObjectAdopter.adoptAndAttach(...)` and `ModelObjectAdoptSupport.adopt(...)`.
  - This is the old adoption/import path, not the custom formatter path.
- `java.lang.reflect.InvocationTargetException`
  - This is only a reflection wrapper around the real validator failure.
  - Root cause: `java.lang.StringIndexOutOfBoundsException: begin 1, end 0, length 1`
  - Failing EDT method: `com._1c.g5.v8.dt.bsl.validation.BslJavaValidator.checkStringLiteral(...)`.
  - Interpretation: EDT BSL validator received a malformed/edge string literal token, likely text length 1. No custom formatter class is present in this stack.

## 2026-06-16 - Safer Formatter Attempt

Current installed test build:

`ru.xelgo.edt.contextlinks.ui_1.1.1.v202606160015.jar`

Current implementation in `ContextLinksBslFormatter`:

- For non-`ChangeAndValidate` methods, delegate to the standard `BslFormatter2`.
- For `ChangeAndValidate` methods:
  - collect all `RegionPreprocessorType.INSERT` ranges;
  - run the standard statement formatter through `IFormattableDocument.withReplacerFilter(...)`;
  - keep only replacements whose text region intersects an insert block;
  - add a high-priority boundary fallback:
    - after `#Вставка`: `newline + markerIndent + "\t"`;
    - before `#КонецВставки`: `newline + markerIndent`;
  - keep original code outside insert blocks untouched.

Reason for this variant:

- Formatting only `region.getItem()` does not know the outer `Функция` / `Если` indentation context and produces too-shallow indentation.
- Formatting the entire method is unsafe because EDT intentionally skips `ChangeAndValidate` methods to avoid modifying copied vendor code.
- The filtered approach lets the native formatter calculate inner insert formatting while the fallback guarantees the marker/body boundary indentation for simple insert blocks.

Build verification:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\redeploy-edt-main.ps1 -Workspace 'C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH' -ForceKill -MaxHeap 20g
```

Result: build passed, EDT restarted, no compiler warnings.

### UI Injector Fix

The runtime `bindIFormatter2()` registration is not enough for the real editor command.
`Ctrl+Shift+F` in an opened BSL module uses the BSL UI injector, so the custom formatter must also be registered through:

```xml
<extension point="com._1c.g5.v8.dt.bsl.ui.bslUiModuleExtension">
  <bslUiModuleExtension
        module="ru.xelgo.edt.contextlinks.core.ContextLinksBslFormatterUiModule">
  </bslUiModuleExtension>
</extension>
```

After adding `ContextLinksBslFormatterUiModule`, EDT invoked `ContextLinksBslFormatter.formatMethod(...)` from the live editor formatter command.

### Live EDT Verification

Installed test build:

`ru.xelgo.edt.contextlinks.ui_1.1.1.v202606160106.jar`

Workspace:

`C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`

Checked module:

`C:\Users\USER\git\uh_main\cf.МагнитМаркет\src\CommonModules\АдресныйКлассификаторБП\Module.bsl`

Result after focusing the BSL editor, running `Ctrl+Shift+F`, and saving:

```bsl
	Если ТипЗнч(КодРегиона) = Тип("Строка") Тогда
		#Вставка
			ТестированиеСистемы = "";
		#КонецВставки
		КодРегионаСтрока = КодРегиона;
```

Fresh `.metadata\.log` after the check contains no formatter errors, no `InvocationTargetException`, and no transaction error from the custom formatter path.

## 2026-06-16 - Insert Block Formatting Iterations

### Text-Only Normalizer

Attempt:

- For `&ИзменениеИКонтроль` / `&ChangeAndValidate` methods, skip the native `BslFormatter2.formatMethod(...)`.
- Locate `RegionPreprocessorType.INSERT`.
- Replace the whole text range from `#Вставка` to `#КонецВставки`.
- Recompute marker and body indentation manually.

Result:

- Marker indentation became stable.
- Nested blocks such as `Если ... Тогда / Пока ... Цикл / КонецЦикла` were indented correctly.
- This was not enough because the text-only pass did not apply normal EDT spacing rules, for example:

```bsl
Пока 1>0 Цикл
ТестированиеСистемы="";
```

Expected:

```bsl
Пока 1 > 0 Цикл
ТестированиеСистемы = "";
```

Conclusion:

- A pure text normalizer is too weak. We still need the native formatter for intra-line BSL spacing.

### Hybrid Native Formatter Attempt

Attempt:

- Run the native formatter only for insertion content.
- Reject native replacements that affect line breaks or line-prefix indentation.
- Keep native replacements inside the line, hoping it would handle operators and spaces.
- After that, run the custom indentation pass for `#Вставка` lines.

Implementation variants tried:

- `formatPreprocessorItem(item, insertOnlyDocument)` for `region.allPreprocessorItems()`.
- `formatRegionPreprocessor(region, insertOnlyDocument)` to stay closer to the old working path.

Diagnostics from `EDT UH\.metadata\.log`:

```text
EDT Extension Tweaks [formatter.method] method=ММ_ПредставлениеРегионаПоКоду insertRanges=1
EDT Extension Tweaks [formatter.region] method=ММ_ПредставлениеРегионаПоКоду items=2 item=com._1c.g5.v8.dt.bsl.model.impl.PreprocessorItemStatementsImpl
EDT Extension Tweaks [formatter.replacer] keep=true lineStructure=false ... around='...Пока 1>0 Цикл...'
EDT Extension Tweaks [formatter.replacer] keep=true lineStructure=false ... around='...ТестированиеСистемы\t\t="";...'
```

Observed output after formatting:

```bsl
		#Вставка
			Если Истина Тогда
				Пока 1>0 Цикл
					ТестированиеСистемы		="";
				КонецЦикла;
			КонецЕсли;
		#КонецВставки
```

Conclusion:

- The native formatter is invoked for the insert block and creates intra-line replacers.
- However, when kept as-is, some native intra-line replacements can produce tab alignment before `=`.
- The next attempt should not blindly keep every native single-line whitespace replacement. We need either:
  - inspect produced replacement text before accepting it; or
  - keep native structural formatting where useful and run a small post-pass for common inline operator spacing inside insert blocks.

### Hybrid With Operator-Spacing Override

Attempt:

- Keep `formatRegionPreprocessor(region, insertOnlyDocument)` as the native formatter entry point.
- Reject native replacements that affect:
  - line breaks;
  - line-prefix indentation;
  - whitespace adjacent to binary operator characters.
- Add a custom insert-only operator spacing pass for code outside string literals and comments.
- The custom pass currently normalizes spacing around:
  - `=`
  - `<`, `>`, `<>`, `<=`, `>=`
  - `+`, `*`, `/`
- Keep the custom insert indentation pass after that.

Reason:

- Logs showed native formatter calls around `Пока 1>0 Цикл` and `ТестированиеСистемы=""`, but accepting all native inline whitespace produced:

```bsl
ТестированиеСистемы		="";
```

- Therefore native operator whitespace needs to be either inspected at replacement-render time or overridden. The current iteration uses the simpler override.

Installed diagnostic build:

`ru.xelgo.edt.contextlinks.ui_1.1.1.v202606160529.jar`

### Successful Verification

Checked in workspace:

`C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`

Checked file:

`C:\Users\USER\git\uh_main\cf.МагнитМаркет\src\CommonModules\АдресныйКлассификаторБП\Module.bsl`

Action:

- Focus BSL editor.
- Run `Ctrl+Shift+F`.
- Save the module.

Verified output on disk:

```bsl
	Если ТипЗнч(КодРегиона) = Тип("Строка") Тогда
		#Вставка
			Если Истина Тогда
				Пока 1 > 0 Цикл
					ТестированиеСистемы = "";
				КонецЦикла;
			КонецЕсли;
		#КонецВставки
		КодРегионаСтрока = КодРегиона;
```

Conclusion:

- `#Вставка` and `#КонецВставки` are indented relative to the surrounding code block.
- Insert body statements are indented one level deeper than `#Вставка`.
- Nested `Если` / `Пока` blocks inside the insert body are indented consistently.
- Common inline operator spacing works for the checked case: `1>0` became `1 > 0`, and `ТестированиеСистемы=""`
  became `ТестированиеСистемы = ""`.
- Code outside insert blocks is not formatted by the plugin path.

### Release Cleanup

Before release, diagnostic formatter logs were removed from runtime:

- `formatter.method`
- `formatter.region`
- `formatter.replacer`

Other expected service-registration and scope-extension diagnostics were moved to `ContextLinks.logDebug(...)`.
They are silent by default and can be enabled only with:

```text
-Dru.xelgo.edt.contextlinks.ui.debug=true
```

Release version prepared for this feature: `1.1.3`.
