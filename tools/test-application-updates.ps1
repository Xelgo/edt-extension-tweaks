param(
    [Parameter(Mandatory = $true)]
    [string]$EdtHome,
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
$bundle = Join-Path $ProjectRoot "bundles\ru.xelgo.edt.contextlinks.ui"
$classes = Join-Path $bundle "target\classes"
if (-not (Test-Path -LiteralPath (Join-Path $classes "ru\xelgo\edt\contextlinks\core\ContextLinks.class"))) {
    throw "Build the plugin with Maven before running these tests."
}

$plugins = Join-Path (Resolve-Path -LiteralPath $EdtHome).Path "plugins\*"
$output = Join-Path $ProjectRoot "target\application-update-tests"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = "$output;$classes;$plugins"
$test = Join-Path $ProjectRoot "tests\ru\xelgo\edt\contextlinks\core\ApplicationUpdateProjectsTest.java"

# Production code is compiled by Tycho; reject stale output before exercising it.
foreach ($relativePath in @(
    "ru\xelgo\edt\contextlinks\core\ContextLinks",
    "ru\xelgo\edt\contextlinks\core\ContextLinksInfobaseSynchronizationManagerProxy",
    "ru\xelgo\edt\contextlinks\ui\ConfigureApplicationUpdateProjectsHandler"
)) {
    $source = Get-Item -LiteralPath (Join-Path $bundle "src\$relativePath.java")
    $compiled = Get-Item -LiteralPath (Join-Path $classes "$relativePath.class")
    if ($source.LastWriteTimeUtc -gt $compiled.LastWriteTimeUtc) {
        throw "Rebuild the plugin with Maven before running tests: $relativePath"
    }
}
& javac -encoding UTF-8 --release 17 -classpath $classpath -d $output $test
if ($LASTEXITCODE -ne 0) {
    throw "Application update test compilation failed."
}
& java -classpath $classpath org.junit.runner.JUnitCore ru.xelgo.edt.contextlinks.core.ApplicationUpdateProjectsTest
if ($LASTEXITCODE -ne 0) {
    throw "Application update regression tests failed."
}
