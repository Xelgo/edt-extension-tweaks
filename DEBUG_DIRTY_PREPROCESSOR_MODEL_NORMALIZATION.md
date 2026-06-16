# Dirty Preprocessor Model Normalization

## Цель

Исследовать, можно ли сделать так, чтобы EDT корректно строила BSL-модель для модулей расширений с "грязными" блоками конфигуратора:

- `#Вставка` / `#КонецВставки` внутри конструкций языка, например перед `Иначе`.
- `#Вставка` / `#КонецВставки` внутри многострочного текста запроса.
- `#Удаление` / `#КонецУдаления`, которое для анализа должно исчезать вместе с содержимым.

Главная идея: для построения модели отдавать парсеру не физический файл, а виртуально нормализованный текст.

## Что ломается в EDT

EDT строит модель через Xtext-грамматику BSL. В грамматике вставка и удаление поддержаны, но только как полноценные элементы модели: module item, method item, statement или expression.

Например `PreprocessorStatementInnerInsert` выглядит концептуально так:

```xtext
BEGIN_INSERT
    item=PreprocessorItemStatement
END_INSERT
```

Это работает, когда внутри вставки лежит полноценный statement:

```bsl
#Вставка
Сообщить("Тест");
#КонецВставки
```

Но это не работает, когда внутри вставки лежит часть конструкции:

```bsl
Если КодРегиона = 0 Тогда
    Возврат Неопределено;
#Вставка
Иначе
    Сообщить("Тест");
#КонецВставки
КонецЕсли;
```

`Иначе` не является отдельным statement. Это часть `IfStatement`, поэтому AST не может построиться, пока маркеры вставки видны парсеру.

Та же проблема возникает в тексте запроса:

```bsl
Запрос.Текст = "ВЫБРАТЬ
               | Поле1,
               #Вставка
               | Поле2,
               #КонецВставки
               | Поле3";
```

Многострочный строковый литерал в грамматике состоит из строк, начинающихся с `|`. Строки `#Вставка` и `#КонецВставки` разрывают литерал до построения AST.

## Вывод по уровню вмешательства

Лечить это после AST ненадежно: AST может не построиться вообще или построиться с синтаксическими ошибками. Значит вмешательство должно быть до парсера или прямо на входе парсера.

Плохие варианты:

- Дописывать грамматику. Слишком дорого и хрупко: нужно научить grammar принимать вставки внутри `If`, query strings, выражений и многих частных production.
- Лечить scope/model после построения. Поздно, потому что ошибка синтаксическая.
- Менять generated parser/lexer. Высокий риск несовместимости между версиями EDT.

Лучший найденный вариант:

- Нормализовать исходный поток перед `BslResource.doLoad()`.
- Сохранять длину текста и переводы строк.
- Не менять физический файл.

## Найденная точка входа

В `com._1c.g5.v8.dt.bsl.resource.BslResource#doLoad(InputStream, Map)` уже есть штатный вызов:

```java
IBslResourceExtension extension = resourceExtensionManager.getResourceExtension();
InputStream stream = extension.replaceStreamIfNecessary(resource, inputStream, options);
super.doLoad(stream, options);
```

Интерфейсы находятся в `com._1c.g5.v8.dt.core.resource.extension`:

```java
public interface IBslResourceExtension {
    InputStream replaceStreamIfNecessary(Resource resource, InputStream stream, Map<?, ?> options);
    InputStream replaceStreamIfNecessary(IFile file, InputStream stream);
}

public interface IBslResourceExtensionManager {
    void setBslResourceExtension(IBslResourceExtension extension);
    IBslResourceExtension getResourceExtension();
}
```

Проверка по установленным jar:

- EDT 2025.2.x: интерфейсы есть, сигнатуры совпадают.
- EDT 2026.1.x: интерфейсы есть, сигнатуры совпадают.

Это хороший кандидат для реализации без bytecode weaving.

## Как должна работать нормализация

Важно не удалять символы физически, а заменять ненужные символы пробелами. Переводы строк нужно оставить как есть.

Причина: Xtext node model, diagnostics, navigation, content assist и formatter работают через offsets. Если укоротить текст, все координаты после первой вставки сдвинутся.

Правило:

- `#Вставка` и `#КонецВставки`: заменить только строки/директивы маркеров пробелами той же длины, содержимое вставки оставить.
- `#Удаление` и `#КонецУдаления`: заменить весь блок пробелами той же длины, включая маркеры и содержимое.
- `\r`, `\n`, `\r\n` не трогать.

Пример:

```bsl
Если КодРегиона = 0 Тогда
    Возврат Неопределено;
#Вставка
Иначе
    Сообщить("Тест");
#КонецВставки
КонецЕсли;
```

Парсер должен увидеть эквивалент:

```bsl
Если КодРегиона = 0 Тогда
    Возврат Неопределено;

Иначе
    Сообщить("Тест");

КонецЕсли;
```

Но с той же длиной и теми же offset.

Для запроса это тоже работает: строки маркеров становятся hidden whitespace, а строки `| ...` снова образуют нормальный многострочный литерал.

## Рекомендованный алгоритм

Первый безопасный вариант: line-aware scanner.

1. Читать текст в кодировке ресурса.
2. Идти по строкам, сохраняя исходные line separator.
3. Если первая не whitespace-лексема строки равна одному из маркеров:
   - `#Вставка` / `#Insert`: заменить саму строку маркера пробелами.
   - `#КонецВставки` / `#EndInsert`: заменить саму строку маркера пробелами.
   - `#Удаление` / `#Delete`: включить режим удаления и заменить строку пробелами.
   - `#КонецУдаления` / `#EndDelete`: заменить строку пробелами и выключить режим удаления.
4. Если режим удаления активен, заменять все не-newline символы строки пробелами.
5. В остальных случаях строку не менять.

Почему line-aware, а не простой replace:

- Нельзя случайно менять `"#Вставка"` внутри обычной строковой константы.
- Нельзя менять `| #Вставка` внутри текста запроса, если это реальная строка запроса, а не директива.
- В конфигураторе директивы вставки/удаления практически всегда являются отдельными препроцессорными строками.

## Incremental parsing: главный риск

`IBslResourceExtension` вызывается в `BslResource#doLoad()`, то есть при полной загрузке ресурса.

Но `BslResource` также имеет:

```java
public void reparse(String text)
public void update(int offset, int replacedLength, String replacement)
```

Эти методы вызывают `DerivedStateAwareResource` напрямую и не проходят через `IBslResourceExtension`.

Следствия:

- Для batch build и первичной загрузки ресурса нормализация через `IBslResourceExtension` должна помочь.
- Для открытого редактора после ручного редактирования возможны ситуации, когда incremental update снова увидит "грязные" маркеры.
- Если пользователь редактирует внутри уже нормализованного `#Удаление`, частичный reparse может получить только замененный фрагмент и не знать, что он находится внутри удаляемого блока.

Для первой реализации можно ограничиться full-load нормализацией и проверить:

- пересборку проекта;
- открытие модуля;
- первичную диагностику;
- content assist после открытия.

Если редактор все еще ломается после правок в dirty-блоке, понадобится второй слой:

- либо кастомный `BslResource`, который переопределит `reparse(String)` и `update(...)`;
- либо кастомный `IParser`, который нормализует `parse(Reader)` и осторожно нормализует `ReplaceRegion` в `reparse(...)`;
- либо fallback на full reparse для dirty-файлов.

Самый надежный, но потенциально более тяжелый вариант для редактора:

- хранить raw-текст ресурса;
- на `update(...)` обновлять raw-текст;
- если ресурс содержит dirty-блоки, делать full `reparse(normalizedRawText)`;
- включить это только для файлов, где реально есть `#Вставка/#Удаление`.

Риск: full reparse на каждый ввод в больших модулях может быть заметен. Поэтому этот вариант лучше включать только при необходимости.

## Производительность

Нормализация полным проходом O(n) по тексту модуля.

Ожидаемо дешево относительно:

- Xtext parse;
- derived state;
- resolve cross references;
- построения типов и scope.

Ключевые оптимизации:

- Сначала быстрый `indexOf('#')`; если `#` нет, вернуть исходный stream без копирования.
- Если нет слов `Вставка`, `Insert`, `Удаление`, `Delete`, `КонецВставки`, `EndInsert`, `КонецУдаления`, `EndDelete`, вернуть исходный stream.
- Создавать новый byte array только если найдена реальная директива.
- Логировать агрегированно: имя ресурса, число insert markers, delete blocks, replaced chars. Не логировать весь текст.

Для build это должно быть дешевле текущих scope-вмешательств: работа линейная, без обхода workspace и без транзакций BM.

## Надежность и edge cases

Нужно обязательно проверить:

- Вставка перед `Иначе`.
- Вставка перед `ИначеЕсли`.
- Вставка внутри многострочного query text.
- Удаление обычных statements.
- Удаление строк query text.
- Несколько блоков в одном методе.
- Вложенные insert/delete. Если конфигуратор допускает вложенность, нужен счетчик глубины, а не boolean.
- Русские и английские маркеры: `#Вставка/#КонецВставки`, `#Insert/#EndInsert`, `#Удаление/#КонецУдаления`, `#Delete/#EndDelete`.
- CRLF и LF.
- UTF-8 BOM.
- Строки и комментарии, где встречается текст `#Вставка`, но строка не является директивой.

Пока не стоит поддерживать замену маркеров, написанных прямо внутри строки кода не с начала строки. Это рискованно, потому что можно повредить строковые литералы. Если найдется реальный пример такого кода, нужно будет добавить более умный lexer-aware scanner.

## Проверка на реальном расширении

Быстрый поиск по `C:\Users\USER\git\uh_main\cf.МагнитМаркет\src` показал:

- около 1688 активных строк `#Вставка/#КонецВставки/#Удаление/#КонецУдаления`;
- около 29 закомментированных строк вида `// #Вставка`;
- встречаются маркеры с разными отступами и хвостовыми пробелами;
- есть реальные вставки внутри query text:

```bsl
|   ДанныеДокумента.НомерТабЧасти КАК НомерТабЧасти,
#Вставка
//++FAE 24.01.2023 Добавлен Работник организации
|   АктСверкиВзаиморасчетов.ММ_РаботникиОрганизаций КАК РаботникОрганизации,
#КонецВставки
|   АктСверкиВзаиморасчетов.СОбособленнымиПодразделениями КАК СОбособленнымиПодразделениями,
```

Следствие для алгоритма: директивой считаем только строку, где первый не-пробельный символ - `#`. Строки `// #Вставка` и строки query text, начинающиеся с `|`, не трогаем.

## План прототипа

1. Добавить `DirtyPreprocessorNormalizer` с pure-функцией:

   ```java
   NormalizationResult normalize(String source)
   ```

2. Покрыть unit-like тестами на обычном Java-коде без запуска EDT.
3. Добавить `ContextLinksBslResourceExtension implements IBslResourceExtension`.
4. На старте плагина получить `IBslResourceExtensionManager` через `ServiceAccess.get(...)`.
5. Если manager пустой, установить наш extension.
6. Если manager уже занят, залогировать warning и не подменять.
7. Проверить build и открытие модулей.

## Первая реализация

2026-06-16 добавлен прототип:

- `DirtyPreprocessorNormalizer` - line-aware, offset-preserving нормализация.
- `ContextLinksBslResourceExtension` - реализация `IBslResourceExtension`.
- `ContextLinksBslResourceExtensionRegistrar` - регистрация через `IBslResourceExtensionManager`.
- Регистрация вызывается из activator, UI startup и BSL runtime module, чтобы не зависеть от порядка старта.
- Добавлен import пакета `com._1c.g5.v8.dt.core.resource.extension`.
- Есть выключатель: `-Dru.xelgo.edt.contextlinks.ui.dirtyPreprocessor.disable=true`.

Локальная проверка:

- `mvn -DskipTests package` через локальный Maven прошел успешно на target platform EDT 2025.2.6.
- Smoke-тест нормализатора подтвердил:
  - вставка перед `Иначе` сохраняет `Иначе`;
  - вставка внутри query text сохраняет строку `|...`;
  - `#Удаление/#КонецУдаления` маскирует содержимое блока;
  - `// #Вставка` не меняется;
  - длина текста после нормализации не меняется.

Следующая проверка должна быть уже в EDT:

- открытие dirty-модуля;
- полная сборка проекта;
- диагностика и подсказка в модуле;
- поведение после ручного редактирования внутри dirty-блока.

## Предварительный вывод

Фича выглядит реализуемой.

Наиболее правильная первая итерация: offset-preserving normalization через `IBslResourceExtension`.

Это должно чинить именно корень проблемы: грязные директивы перестают ломать синтаксический разбор, а вставленный код и текст запросов остаются нативными для EDT.

Самый большой технический риск не в производительности, а в incremental parsing открытого редактора. Поэтому первую версию нужно проверять отдельно на batch build и отдельно на живом редактировании dirty-модуля.

## Runtime install smoke

2026-06-16 прототип был установлен в workspace `C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`.

Первая установка показала важную ошибку старта: `ContextLinksPlugin.start()` вызывал регистрацию
`IBslResourceExtensionManager` слишком рано, когда сервис EDT еще не был опубликован через wiring. Из-за этого
`ServiceAccess.get(IBslResourceExtensionManager.class)` бросал `ServiceUnavailableException`, bundle
`ru.xelgo.edt.contextlinks.ui` падал при старте, а вся фича не загружалась.

Исправление: регистрация dirty preprocessor extension стала мягкой. Если manager еще недоступен, плагин не падает,
а запускает системный Eclipse `Job` с повторными попытками регистрации. В логе корректная последовательность:

- сначала `dirty preprocessor normalization not registered yet: manager unavailable`;
- затем, после старта сервисов EDT, `dirty preprocessor normalization registered`.

Вывод: регистрацию сервисов EDT, которые приходят из wiring, нельзя считать доступной в activator сразу после
`Plugin.start()`. Для таких точек расширения нужен delayed/retry registration без падения bundle.

## Диагностика проверки открытого модуля

После установки первого прототипа `СозданиеНаОсновании/Module.bsl` все еще показывал ошибки проверки на строках
`#КонецУдаления` и около закрытия `КонецЕсли`. При этом `.metadata/.log` подтвердил, что `IBslResourceExtension`
срабатывает именно на этом файле:

- `source=/cf.МагнитМаркет/src/CommonModules/СозданиеНаОсновании/Module.bsl`
- `insertMarkers=2`
- `deleteBlocks=2`
- `maskedChars=320`

Предварительный вывод: full-load ресурс получает нормализованный текст, но проверка открытого редактора может идти
через другой путь Xtext parser/reparse и видеть исходные dirty-директивы.

2026-06-16 добавлен диагностический слой:

- `DirtyPreprocessorLogger` логирует `stage`, `source`, `thread`, длину текста, количество маркеров и preview
  нормализованного окна вокруг первых dirty-блоков.
- `ContextLinksBslResourceExtension` теперь пишет stage `file` или `resource`.
- Добавлен `ContextLinksBslParser extends BslParser`.
- В `ContextLinksBslRuntimeModule` добавлен `bindIParser()`, чтобы нормализовать текст также на входе
  `parser.doParse(...)`, `parser.parse(...)` и частично `parser.reparse(...)`.

Диагностический билд `1.1.3.v202606160645` установлен в workspace
`C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH`.

Следующая проверка: открыть/проверить модуль `СозданиеНаОсновании` и сравнить, появились ли в `.metadata/.log`
сообщения `dirty preprocessor normalized stage=parser...`. Если parser stage появляется, но диагностика остается,
значит ошибка рождается еще ниже/выше parser hook. Если parser stage не появляется, runtime module binding не
участвует в этом validation-проходе.

## ChangeAndValidate method text validation

2026-06-16 found why the remaining diagnostic appears:

`BslJavaValidator.checkChangeAndValidateMethods(...)` already has built-in Configurator semantics for
`#Вставка/#КонецВставки/#Удаление/#КонецУдаления` while comparing `&ИзменениеИКонтроль` methods with the base method.
It removes inserted lines and marker lines from the extension method content, so the comparison sees the original base
method body.

Our resource-level normalizer hides those markers before the validator reads node text. As a result, the parser sees a
valid active method body, but the validator can no longer apply its own dirty-block comparison logic and reports:

`Текст метода имеет отличия от базового метода, что недопустимо в случае расширения "Изменение и контроль"`

Patch direction:

- keep normalized text for parser/model;
- weave `BslJavaValidator.getMethodContent(Method)`;
- for methods whose original source slice contains dirty blocks, read the original source text by the same node offsets
  and return raw method body lines to the stock validator;
- let the stock validator remove insert blocks and marker lines exactly as before.

This keeps the syntax fix and restores the native `ИзменениеИКонтроль` comparison semantics.

Follow-up after runtime check: BSL class weaving did not activate in the installed EDT session. The log had no
`BSL validator weaving active` / `patched BSL validator method content`, so the validator class was probably loaded
before the plugin activator registered the weaving service.

Second approach:

- use the official BSL runtime module extension point;
- bind `BslJavaValidator` to `ContextLinksBslJavaValidator`;
- keep all stock validation;
- override only `error(...)`;
- suppress only issue code `method-text-has-differences-base-method` when the `EObject` is a `Method` whose original
  source slice contains dirty preprocessor blocks.

This is more robust than weaving because the extension module is part of BSL Guice injector creation.

Runtime check confirmed this binding path works: after installing `1.1.3.v202606160706`, `.metadata/.log` contains
`EDT Extension Tweaks BSL Java validator override created`.

Safety refinement:

- do not suppress every method text difference just because the method has dirty blocks;
- resolve the base method through `IModuleExtensionService.getSourceMethod(method)`;
- build comparable content from the raw dirty method text using EDT's stock rule:
  - remove `#Вставка/#КонецВставки` and all inserted lines;
  - remove only `#Удаление/#КонецУдаления` marker lines, keeping deleted base lines;
- suppress `method-text-has-differences-base-method` only when this comparable content equals the base method content.

This preserves the native protection against accidental edits outside Configurator dirty blocks.

## Service-comment normalization and strict validation

2026-06-16 user found a critical safety case: if an extension method contains legal dirty blocks and also has a normal
manual edit outside those blocks, the plugin must not suppress EDT's native
`method-text-has-differences-base-method` diagnostic.

Example:

```bsl
#Вставка
Если ПодменюСоздатьНаОсновании = Неопределено Тогда
    Хер = 0;
#КонецВставки
...
Ломаем = 1;
```

`Хер = 0;` is allowed because it belongs to `#Вставка`. `Ломаем = 1;` is not allowed because it is a regular method
body change and must still trigger EDT's ChangeAndValidate check.

Patch:

- AST/model normalization no longer turns dirty directives into blank whitespace. It replaces them with short service
  comments that preserve offsets:
  - `#Вставка` -> `//ETW+I`
  - `#КонецВставки` -> `//ETW-I`
  - `#Удаление` -> `//ETW+D`
  - `#КонецУдаления` -> `//ETW-D`
  - body lines inside deletion -> `//ETW~D`
- The parser still sees valid BSL without dirty directives breaking partial syntax structures.
- The validator does not trust only the physical file on disk. It stores a mapping from normalized AST text to the raw
  text that produced it, so unsaved editor changes are checked against the current model text.
- The custom validator suppresses `method-text-has-differences-base-method` only when the raw extension method becomes
  byte-for-byte comparable with the base method after applying Configurator dirty-block semantics:
  - insertion markers and inserted lines are removed;
  - deletion markers are removed, but deleted base lines remain;
  - any normal line outside dirty blocks remains and therefore keeps the native error alive.

This is intentionally stricter than "method contains dirty blocks, so suppress the diagnostic". Dirty blocks explain only
their own content, not unrelated edits.

## BSL validator weaving

Runtime check on `СозданиеНаОсновании` showed that normalizer logs appear, but the custom
`ContextLinksBslJavaValidator.error(...)` path is not reliable enough as the primary fix: in some validation passes there
is no `dirty ChangeAndValidate comparison` log, so relying on a subclass can miss the actual EDT validator instance.

The more stable patch point is the private EDT method:

```java
BslJavaValidator.getMethodContent(Method)
```

`checkChangeAndValidateMethods(...)` calls this method before applying EDT's native Configurator dirty-block comparison.
The plugin now weaves `com._1c.g5.v8.dt.bsl.validation.BslJavaValidator#getMethodContent(Method)` and redirects it to
`ContextLinksBslValidatorPatches.getMethodContent(Method)`.

Effect:

- parser/model still receives normalized text with service comments;
- ChangeAndValidate comparison receives raw method content when the method was built from dirty-preprocessed source;
- EDT's own logic still removes `#Вставка` blocks and keeps `#Удаление` body lines for comparison;
- normal edits outside dirty blocks, such as `Ломаем = 1;`, remain real differences and must still produce the native
  diagnostic.

The manifest must supplement both bundles:

```text
Eclipse-SupplementBundle: com._1c.g5.v8.dt.qw.ui,
 com._1c.g5.v8.dt.bsl
```

Expected startup log after a successful weave:

```text
EDT Extension Tweaks weaving active for com._1c.g5.v8.dt.bsl
EDT Extension Tweaks patched BSL validator method content
```

## Safety correction: no diagnostic suppression

2026-06-16 follow-up: the temporary `ContextLinksBslJavaValidator` subclass was removed from the runtime module.
That subclass intercepted `method-text-has-differences-base-method` and could make the implementation look like it was
just suppressing EDT's ChangeAndValidate diagnostic.

The intended behavior is stricter:

- `#Insert` / `#Вставка` marker lines are converted to service comments only for parsing;
- code inside insertion blocks stays in the AST and must still be checked by the normal BSL validator;
- `#Delete` / `#Удаление` blocks are hidden from the AST because this is base code removed by the extension;
- the ChangeAndValidate method-text check must remain native and strict for ordinary edits outside dirty blocks.

The remaining validator patch point is `BslJavaValidator#getMethodContent(Method)`: it should provide raw method text to
EDT's own comparison logic instead of suppressing diagnostics after they are produced.

## Lowercase content contract

2026-06-16: decompilation of EDT 2025.2 `BslJavaValidator#checkChangeAndValidateMethods(...)` showed an important
contract of the private `getMethodContent(Method)` helper. The ChangeAndValidate comparison creates lowercase marker
sets:

- `#вставка` / `#insert`;
- `#конецвставки` / `#endinsert`;
- `#удаление` / `#конецудаления` / `#delete` / `#enddelete`.

Then it compares those sets with content lines directly. Therefore `getMethodContent(Method)` must return trimmed,
non-empty, lowercase lines. Returning raw-case lines such as `#Вставка` prevents EDT from recognizing insertion markers,
so the inserted lines stay in the comparison and produce a false
`method-text-has-differences-base-method` diagnostic.

Patch: `ContextLinksBslValidatorPatches.toContentLines(...)` now lowercases every trimmed non-empty line before returning
it to the native EDT validator.

## 2026-06-16 no native component patch route

The BSL validator weaving approach was rejected. It works technically, but it patches the native EDT validator class at
runtime and is too close to replacing a platform component.

Current chosen route:

- do not modify or replace `com._1c.g5.v8.dt.bsl` jars;
- do not weave `BslJavaValidator#getMethodContent(Method)`;
- keep Query Wizard weaving only for Query Wizard classes;
- register `ContextLinksBslJavaValidator` via the official `bslRuntimeModuleExtension` / Guice
  `bindBslJavaValidator()` hook;
- let the native `checkChangeAndValidateMethods(...)` run normally;
- intercept only issue code `method-text-has-differences-base-method`;
- suppress it only when `ContextLinksBslValidatorPatches.hasOnlyDirtyPreprocessorDifference(method)` proves that the
  extension method and base method are equal after Configurator dirty-block semantics:
  insertion block lines are ignored, deletion block markers are ignored, deletion body remains.

This keeps ordinary edits outside dirty blocks visible to EDT. A line like `Breaks = 1;` outside `#Insert/#Delete` must
still produce the native ChangeAndValidate diagnostic.

Runtime verification for build `1.1.3.v202606160838`:

- installed plugin contains `ContextLinksBslJavaValidator`;
- `ContextLinksBslRuntimeModule` binds it;
- `ContextLinksQueryWizardWeavingServiceFactory` no longer contains `getMethodContent`, `patchBslJavaValidator`, or
  `patched BSL validator`;
- startup log contains `EDT Extension Tweaks BSL Java validator override created`.

## 2026-06-16 safety check for real method changes

User test case:

- method `СозданиеНаОсновании.ММ_СкрытьСтандартноеПодменюВводаНаОсновании`;
- `#Удаление/#КонецУдаления` contains the original `Если ... Тогда`;
- `#Вставка/#КонецВставки` contains the replacement `Если ... и Истина и Истина Тогда` plus inserted body;
- a normal line `Тест = 1;` is added outside all dirty blocks.

Expected behavior: dirty-block changes are allowed, but `Тест = 1;` outside dirty blocks must still raise the native
ChangeAndValidate diagnostic:

```text
method-text-has-differences-base-method
```

Important observation from logs: with the parser-normalized model, EDT did not call our `error(...)` interceptor for this
diagnostic. The normalizer was active, but there was no `ChangeAndValidate diagnostic intercepted` log entry. This means
there may be no native diagnostic to suppress/intercept in this model state.

Current fix:

- keep the native validator and do not patch EDT jars;
- keep the existing `error(...)` suppression only for native diagnostics that are proven dirty-only;
- add a plugin-owned `@Check` method in `ContextLinksBslJavaValidator` for methods that contain dirty preprocessor
  markers;
- this check reads the raw method text, compares it with the base method using Configurator semantics, and emits the same
  EDT issue code only when a meaningful difference remains;
- raw method extraction no longer trusts only `methodNode.getText().length()`, because normalized AST node boundaries can
  truncate the raw method. It now scans the raw source to `КонецПроцедуры` / `КонецФункции`.

Verification for build `1.1.3.v202606160858`:

```text
EDT Extension Tweaks dirty ChangeAndValidate comparison method=ММ_СкрытьСтандартноеПодменюВводаНаОсновании
  suppressed=false
  reason=meaningful-diff
  diff=line=24 extension="тест = 1;" source="конецпроцедуры"

EDT Extension Tweaks emits dirty ChangeAndValidate method text diagnostic
  method=ММ_СкрытьСтандартноеПодменюВводаНаОсновании
```

This confirms that ordinary edits outside `#Вставка/#Удаление` are no longer hidden by the dirty-block support.

## 2026-06-16 live editor reparse hang guard

User reported a live editor hang while working in:

`АдресныйКлассификаторБП.ММ_ПредставлениеРегионаПоКоду`

The EDT UI thread was waiting in `BslXtextDocument$CustomXtextDocumentLocker.waitUpdatingDataModel(...)`, and the module
contained dirty inserts including a partial `If`-structure case where `#Вставка` wraps an `Иначе` branch.

Patch:

- `ContextLinksBslParser#doReparse(...)` no longer normalizes only the incremental replacement when the previous parse
  result is already a dirty-normalized model with `//ETW` service comments.
- For dirty models, it reconstructs the full raw source from `DirtyPreprocessorSourceCache`, applies the replace region,
  normalizes the whole source again, updates the normalized-source mapping, and calls full `super.doParse(...)`.
- If raw source is unavailable, it falls back to full parsing of the updated normalized text rather than mixing raw dirty
  markers into an already normalized parse tree.

Reason:

Incremental parsing is unsafe for dirty-normalized BSL because the previous node model is offset-preserving but not raw:
it contains service comments in place of configurator markers. Applying raw replacement fragments on top of that model can
leave Xtext/EDT waiting for a model update or validating against stale raw text. The full reparse is a little heavier, but
only for dirty-normalized modules and keeps the parser, node model, and raw-source cache aligned during live editing.
