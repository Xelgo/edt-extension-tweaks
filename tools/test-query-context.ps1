param(
    [Parameter(Mandatory = $true)]
    [string]$EdtHome,
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
$bundle = Join-Path $ProjectRoot "bundles\ru.xelgo.edt.contextlinks.ui"
$classes = Join-Path $bundle "target\classes"
$plugins = Join-Path (Resolve-Path -LiteralPath $EdtHome).Path "plugins\*"
$output = Join-Path $ProjectRoot "target\query-context-tests"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = "$output;$classes;$plugins"
$test = Join-Path $ProjectRoot "tests\ru\xelgo\edt\contextlinks\core\ExternalQueryScopeTest.java"

foreach ($name in @("ContextLinksV8GlobalScopeProviderProxy", "ContextLinksExternalQueryScope")) {
    $relative = "ru\xelgo\edt\contextlinks\core\$name"
    $source = Get-Item -LiteralPath (Join-Path $bundle "src\$relative.java")
    $compiled = Get-Item -LiteralPath (Join-Path $classes "$relative.class")
    if ($source.LastWriteTimeUtc -gt $compiled.LastWriteTimeUtc) {
        throw "Rebuild the plugin with Maven before running tests: $name"
    }
}
& javac -encoding UTF-8 --release 17 -classpath $classpath -d $output $test
if ($LASTEXITCODE -ne 0) { throw "Query context test compilation failed." }
& java -Xverify:all -classpath $classpath org.junit.runner.JUnitCore ru.xelgo.edt.contextlinks.core.ExternalQueryScopeTest
if ($LASTEXITCODE -ne 0) { throw "Query context regression tests failed." }
