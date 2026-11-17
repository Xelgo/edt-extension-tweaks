param(
    [Parameter(Mandatory = $true)]
    [string]$EdtHome,
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
$bundle = Join-Path $ProjectRoot "bundles\ru.xelgo.edt.contextlinks.ui"
$classes = Join-Path $bundle "target\classes"
$relativePath = "ru\xelgo\edt\contextlinks\core\AdoptedFormReload"
$source = Get-Item -LiteralPath (Join-Path $bundle "src\$relativePath.java")
$compiled = Get-Item -LiteralPath (Join-Path $classes "$relativePath.class")
if ($source.LastWriteTimeUtc -gt $compiled.LastWriteTimeUtc) {
    throw "Rebuild the plugin before running tests."
}

$plugins = Join-Path (Resolve-Path -LiteralPath $EdtHome).Path "plugins\*"
$output = Join-Path $ProjectRoot "target\adopted-form-reload-tests"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = "$output;$classes;$plugins"
$test = Join-Path $ProjectRoot "tests\ru\xelgo\edt\contextlinks\core\AdoptedFormReloadTest.java"
$listenerTest = Join-Path $ProjectRoot "tests\ru\xelgo\edt\contextlinks\ui\AdoptedFormReloadListenerTest.java"
& javac -encoding UTF-8 --release 17 -classpath $classpath -d $output $test $listenerTest
if ($LASTEXITCODE -ne 0) {
    throw "Adopted form reload test compilation failed."
}
& java -classpath $classpath org.junit.runner.JUnitCore `
    ru.xelgo.edt.contextlinks.core.AdoptedFormReloadTest `
    ru.xelgo.edt.contextlinks.ui.AdoptedFormReloadListenerTest
if ($LASTEXITCODE -ne 0) {
    throw "Adopted form reload regression tests failed."
}
