# EDT Extension Tweaks

Plugin for 1C:Enterprise Development Tools that improves day-to-day work with extensions, external
reports/processors, query constructor metadata, and infobase update flows.

The plugin is focused on projects that live together in one EDT workspace and need to see each other as one working
context without manually copying metadata between extensions.

## Features

- Adds **Настроить контекст EDT** to configurable workspace projects, including extensions and external
  reports/processors.
- Lets a project use BSL context from selected sibling projects, so content assist can see exported common modules and
  metadata from configured extensions.
- Extends Query Constructor metadata visibility: linked extension objects and fields can be used in query tables and
  fields from another extension or external report/processor.
- Patches Query Constructor table matching so repeated field selection keeps one logical table instead of creating
  duplicate aliases for the same metadata object.
- Filters Query Constructor adoption of metadata from foreign extensions, preventing EDT from trying to add another
  extension's objects into the current extension.
- Adds **Настроить обновляемые проекты** to the Applications view, allowing selected configuration or extension
  projects to be skipped in the infobase update chain.
- Keeps optional debug diagnostics for complex EDT synchronization and Query Constructor scenarios.

## Build

Requirements:

- JDK 17
- Maven 3.9.4+

Build the p2 update site:

```powershell
$env:JAVA_HOME='C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64'
& 'C:\Users\USER\Documents\EDT Plugins\.tools\apache-maven-3.9.9\bin\mvn.cmd' package -DskipTests
```

The installable update site archive is created at:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
```

## Debugging Trail

See [DEBUG_LOG.md](DEBUG_LOG.md) for curated EDT log findings, failed hypotheses, diagnostic commits, and conclusions
from runtime debugging.

## Projects

- `bundles/ru.xelgo.edt.contextlinks.ui` - UI commands, BSL/QL context integration, Query Constructor patches, and
  infobase update tweaks.
- `features/ru.xelgo.edt.contextlinks.feature` - installable feature.
- `repositories/ru.xelgo.edt.contextlinks.repository` - p2 update site.
- `targets/default/default.target` - target platform for EDT 2025.2.
