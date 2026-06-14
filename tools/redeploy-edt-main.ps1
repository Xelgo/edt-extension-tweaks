param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$Workspace = (Join-Path $env:LOCALAPPDATA "1C\1cedtstart\projects\EDTDEV"),
    [string]$EdtHome = (Join-Path $env:LOCALAPPDATA "1C\1cedtstart\installations\1C_EDT (Lite) 2025.2\1cedt"),
    [string]$JavaHome = "C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64",
    [string]$LauncherVm = "C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64\bin\javaw.exe",
    [string]$Maven = (Join-Path (Resolve-Path "$PSScriptRoot\..\..").Path ".tools\apache-maven-3.9.9\bin\mvn.cmd"),
    [string]$Profile = "",
    [string]$InstallIU = "ru.xelgo.edt.contextlinks.feature.feature.group",
    [string]$UninstallIU = "ru.xelgo.edt.contextlinks.feature.feature.group",
    [string[]]$DependencyRepositories = @(
        "https://edt.1c.ru/downloads/releases/ruby/2025.2/",
        "https://download.eclipse.org/releases/2023-12/",
        "http://download.eclipse.org/cbi/updates/license/2.0.2.v20181016-2210"
    ),
    [switch]$SkipBuild,
    [switch]$NoRestart,
    [switch]$ForceKill,
    [switch]$DebugPlugin,
    [switch]$DryRun,
    [int]$ShutdownTimeoutSec = 20
)

$ErrorActionPreference = "Stop"

function Write-Step($Message) {
    Write-Host "==> $Message"
}

function Format-Argument($Argument) {
    if ($Argument -match '[\s"]') {
        return '"' + ($Argument -replace '"', '\"') + '"'
    }
    return $Argument
}

function Invoke-Step($FilePath, [string[]]$Arguments, $WorkingDirectory = $ProjectRoot) {
    $display = (Format-Argument $FilePath) + " " + (($Arguments | ForEach-Object { Format-Argument $_ }) -join " ")
    Write-Step $display
    if ($DryRun) {
        return
    }

    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $display"
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-OptionalStep($FilePath, [string[]]$Arguments, $WorkingDirectory = $ProjectRoot) {
    $display = (Format-Argument $FilePath) + " " + (($Arguments | ForEach-Object { Format-Argument $_ }) -join " ")
    Write-Step $display
    if ($DryRun) {
        return
    }

    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            Write-Step "Optional command failed with exit code ${LASTEXITCODE}; continuing"
        }
    }
    finally {
        Pop-Location
    }
}

function Convert-ToFileUriPath($Path) {
    return (Resolve-Path -LiteralPath $Path).Path.Replace("\", "/")
}

function Convert-ToP2ProfileName($Path) {
    return (Resolve-Path -LiteralPath $Path).Path.Replace(":", "_").Replace("\", "_")
}

function Ensure-BuildPlaceholders {
    $resources = Join-Path $ProjectRoot "bundles\ru.xelgo.edt.contextlinks.ui\resources"
    if (-not (Test-Path -LiteralPath $resources)) {
        Write-Step "Creating missing bundle resources directory $resources"
        if (-not $DryRun) {
            New-Item -ItemType Directory -Force -Path $resources | Out-Null
        }
    }
}

function Get-EdtWorkspaceProcesses {
    $resolvedWorkspace = (Resolve-Path -LiteralPath $Workspace).Path
    $escapedWorkspace = [regex]::Escape($resolvedWorkspace)
    $escapedWorkspaceUriPath = [regex]::Escape($resolvedWorkspace.Replace("\", "/"))
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.CommandLine -and
            ($_.Name -in @("1cedt.exe", "javaw.exe", "java.exe")) -and
            (($_.CommandLine -match $escapedWorkspace) -or ($_.CommandLine -match $escapedWorkspaceUriPath))
        }
}

function Stop-EdtWorkspace {
    $processes = @(Get-EdtWorkspaceProcesses)
    if ($processes.Count -eq 0) {
        Write-Step "No EDT processes found for workspace $Workspace"
        return
    }

    Write-Step "Force killing EDT processes for workspace ${Workspace}: $($processes.ProcessId -join ', ')"
    if ($DryRun) {
        return
    }

    foreach ($cimProcess in $processes) {
        Stop-Process -Id $cimProcess.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Clear-WorkspaceLog {
    $log = Join-Path $Workspace ".metadata\.log"
    if (Test-Path -LiteralPath $log) {
        Write-Step "Deleting workspace log $log"
        if (-not $DryRun) {
            Remove-Item -LiteralPath $log -Force
        }
    }
}

function Sync-ContextLinksBundleInfo {
    $bundlesInfo = Join-Path $EdtHome "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
    $pluginPool = Join-Path $bundlePool "plugins"
    if (-not (Test-Path -LiteralPath $bundlesInfo)) {
        throw "bundles.info not found: $bundlesInfo"
    }

    $latestPlugin = Get-ChildItem -LiteralPath $pluginPool -Filter "ru.xelgo.edt.contextlinks.ui_*.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latestPlugin) {
        throw "Installed contextlinks plugin jar not found in $pluginPool"
    }

    if ($latestPlugin.Name -notmatch '^ru\.xelgo\.edt\.contextlinks\.ui_(.+)\.jar$') {
        throw "Unexpected contextlinks plugin jar name: $($latestPlugin.Name)"
    }

    $version = $Matches[1]
    $pluginUri = "file:/$($latestPlugin.FullName.Replace('\', '/'))"
    $line = "ru.xelgo.edt.contextlinks.ui,$version,$pluginUri,4,false"

    Write-Step "Syncing bundles.info to $($latestPlugin.Name)"
    if ($DryRun) {
        return
    }

    $lines = @(Get-Content -LiteralPath $bundlesInfo)
    $updated = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -like "ru.xelgo.edt.contextlinks.ui,*") {
            $lines[$i] = $line
            $updated = $true
            break
        }
    }

    if (-not $updated) {
        $lines += $line
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllLines($bundlesInfo, $lines, $utf8NoBom)
}

Set-Location -LiteralPath $ProjectRoot

$edtExe = Join-Path $EdtHome "1cedt.exe"
$edtConsoleExe = Join-Path $EdtHome "1cedtc.exe"
$repoZip = Join-Path $ProjectRoot "repositories\ru.xelgo.edt.contextlinks.repository\target\ru.xelgo.edt.contextlinks.repository.zip"
$profile = if ($Profile) { $Profile } else { Convert-ToP2ProfileName $EdtHome }
$bundlePool = Join-Path $env:USERPROFILE ".p2\pool"
$javaBin = Split-Path -Parent $LauncherVm

if (-not (Test-Path -LiteralPath $edtExe)) {
    throw "EDT executable not found: $edtExe"
}

if (-not (Test-Path -LiteralPath $edtConsoleExe)) {
    throw "EDT console executable not found: $edtConsoleExe"
}

if (-not (Test-Path -LiteralPath (Join-Path $javaBin "java.exe"))) {
    throw "java.exe not found near launcher VM: $javaBin"
}

$env:Path = $javaBin + ";" + $env:Path

if (-not $SkipBuild) {
    $env:JAVA_HOME = $JavaHome
    Ensure-BuildPlaceholders
    Invoke-Step $Maven @("clean", "package", "-DskipTests")
}

if (-not (Test-Path -LiteralPath $repoZip)) {
    throw "Update site zip not found: $repoZip"
}

Stop-EdtWorkspace

$repoUri = "jar:file:/$(Convert-ToFileUriPath $repoZip)!/"
$repositories = (@($repoUri) + $DependencyRepositories) -join ","
$directorBaseArgs = @(
    "-nosplash",
    "-application", "org.eclipse.equinox.p2.director",
    "-profile", $profile,
    "-destination", $EdtHome,
    "-bundlepool", $bundlePool,
    "-p2.os", "win32",
    "-p2.ws", "win32",
    "-p2.arch", "x86_64",
    "-roaming",
    "-consoleLog"
)

$installError = $null
try {
    if ($UninstallIU) {
        $uninstallArgs = $directorBaseArgs + @("-uninstallIU", $UninstallIU)
        Invoke-OptionalStep $edtConsoleExe $uninstallArgs $EdtHome
    }

    $installArgs = $directorBaseArgs + @("-repository", $repositories, "-installIU", $InstallIU)
    Invoke-Step $edtConsoleExe $installArgs $EdtHome
    Sync-ContextLinksBundleInfo
}
catch {
    $installError = $_
}

Clear-WorkspaceLog

if (-not $NoRestart) {
    $startArgs = @(
        "-data", $Workspace,
        "--launcher.appendVmargs",
        "-vmargs",
        "-Djava.library.path=",
        "-Duser.language=ru",
        "-Xmx8192m"
    )

    if ($DebugPlugin) {
        $startArgs = $startArgs + @("-Dru.xelgo.edt.contextlinks.ui.debug=true")
    }

    Write-Step "Starting EDT for workspace $Workspace"
    if (-not $DryRun) {
        $startArgumentLine = ($startArgs | ForEach-Object { Format-Argument $_ }) -join " "
        Start-Process -FilePath $edtExe -ArgumentList $startArgumentLine -WorkingDirectory $EdtHome | Out-Null
    }
}

if ($installError) {
    throw $installError
}
