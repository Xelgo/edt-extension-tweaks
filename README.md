# EDT Extension Tweaks

Плагин для 1C:Enterprise Development Tools, который упрощает работу с расширениями, внешними
обработками/отчетами, конструктором запросов и цепочкой обновления информационной базы.

Плагин рассчитан на сценарии, где в одном workspace EDT живут основная конфигурация, несколько расширений и внешние
обработки или отчеты. Он помогает этим проектам видеть общий контекст без ручного копирования метаданных между
расширениями.

## Возможности

- Добавляет команду **Настроить контекст EDT** для проектов расширений, внешних обработок, внешних отчетов и других
  настраиваемых проектов workspace.
- Позволяет проекту использовать BSL-контекст выбранных соседних проектов: экспортные общие модули и метаданные
  становятся доступны в контекстной подсказке EDT.
- Расширяет видимость метаданных в конструкторе запросов: объекты и поля связанных расширений можно использовать в
  запросах из другого расширения, внешней обработки или внешнего отчета.
- Исправляет сопоставление таблиц в конструкторе запросов, чтобы повторный выбор полей одного объекта не создавал
  лишние алиасы и дубли таблиц.
- Фильтрует попытки конструктора запросов добавить в текущее расширение объекты, которые принадлежат другому
  расширению.
- Добавляет команду **Настроить обновляемые проекты** во вкладку приложений EDT: можно исключать тяжелую основную
  конфигурацию или отдельные расширения из цепочки обновления ИБ.
- Сохраняет отладочные диагностические логи для сложных сценариев синхронизации EDT и работы конструктора запросов.

## Короткое описание для GitHub

Плагин для 1C:EDT: общий BSL-контекст между проектами, метаданные расширений в конструкторе запросов и гибкая настройка обновления ИБ.

## Установка и обновление из EDT

В EDT откройте **Справка -> Установить новое ПО...** и добавьте update site:

```text
jar:https://github.com/Xelgo/edt-extension-tweaks/releases/latest/download/edt-extension-tweaks-update-site.zip!/
```

После добавления выберите **EDT Extension Tweaks** и завершите установку. Для обновления плагина можно использовать
тот же update site: ссылка `latest` всегда указывает на последний опубликованный релиз.

Архив update site последнего релиза:

```text
https://github.com/Xelgo/edt-extension-tweaks/releases/latest/download/edt-extension-tweaks-update-site.zip
```

## Сборка

Требования:

- JDK 17
- Maven 3.9.4+

Собрать p2 update site:

```powershell
$env:JAVA_HOME='C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64'
& 'C:\Users\USER\Documents\EDT Plugins\.tools\apache-maven-3.9.9\bin\mvn.cmd' package -DskipTests
```

Архив update site будет создан здесь:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
```

## Журнал отладки

См. [DEBUG_LOG.md](DEBUG_LOG.md): там собраны выводы из логов EDT, неудачные гипотезы, диагностические коммиты и
результаты runtime-отладки.

## Диагностика зависаний EDT

Если EDT завис на сборке большого проекта, снимите диагностику до принудительного закрытия процесса:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\capture-edt-diagnostics.ps1 `
  -Workspace "C:\Users\USER\AppData\Local\1C\1cedtstart\projects\EDT UH" `
  -DurationSec 60 `
  -IntervalSec 10
```

Скрипт найдет Java-процесс EDT по workspace, возьмет `jcmd.exe` из той же JDK, скопирует `.metadata\.log`, снимет
thread dumps, heap info и попробует записать JFR. Результаты складываются в `diagnostics/`.

## Структура проекта

- `bundles/ru.xelgo.edt.contextlinks.ui` - UI-команды, интеграция BSL/QL-контекста, патчи конструктора запросов и
  доработки обновления ИБ.
- `features/ru.xelgo.edt.contextlinks.feature` - устанавливаемая feature.
- `repositories/ru.xelgo.edt.contextlinks.repository` - p2 update site.
- `targets/default/default.target` - target platform для EDT 2025.2.
