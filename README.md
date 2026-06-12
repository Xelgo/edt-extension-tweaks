# EDT Context Links

Plugin for 1C:EDT 2025.2 that lets an EDT project use BSL context from other workspace projects.

The plugin adds the **Настроить контекст EDT** command to the EDT navigator context menu. Select an extension, external data processor/report, or another EDT project, run the command, and choose projects whose context model should be visible in BSL content assist.

The current experimental build intentionally does not replace the BSL `IGlobalScopeProvider`: that route breaks standard EDT content assist in EDT 2025.2.6.4. Instead, the plugin uses the safer official `com._1c.g5.v8.dt.bsl.externalMetaTypesExtension` path. When a project has configured links, it contributes a small probe type named `XelgoContextLinksProbe`.

## Build

Requirements:

- JDK 17
- Maven 3.9.4+

Build the p2 update site:

```powershell
$env:JAVA_HOME='C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64'
& 'C:\Users\Xelgo\Documents\New project\.tools\apache-maven-3.9.9\bin\mvn.cmd' package -DskipTests
```

The installable update site archive is created at:

```text
repositories/ru.xelgo.edt.contextlinks.repository/target/ru.xelgo.edt.contextlinks.repository.zip
```

## Projects

- `bundles/ru.xelgo.edt.contextlinks.ui` - UI command and experimental external meta types provider.
- `features/ru.xelgo.edt.contextlinks.feature` - installable feature.
- `repositories/ru.xelgo.edt.contextlinks.repository` - p2 update site.
- `targets/default/default.target` - target platform for EDT 2025.2.
