param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$Workspace = "C:\Users\Xelgo\AppData\Local\1C\1cedtstart\projects\Main",
    [string]$EdtHome = "C:\Users\Xelgo\AppData\Local\1C\1cedtstart\installations\1C_EDT 2025.2\1cedt",
    [string]$JavaHome = "C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64",
    [string]$LauncherVm = "C:\Program Files\1C\1CE\components\1c-edt-start-0.9.0+277-x86_64\jre\bin\javaw.exe",
    [string]$Maven = "C:\Users\Xelgo\Documents\New project\.tools\apache-maven-3.9.9\bin\mvn.cmd",
    [string]$InstallIU = "ru.xelgo.edt.contextlinks.feature.feature.group",
    [switch]$SkipBuild,
    [switch]$NoRestart,
    [switch]$ForceKill,
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

function Convert-ToFileUriPath($Path) {
    return (Resolve-Path -LiteralPath $Path).Path.Replace("\", "/")
}

function Get-EdtWorkspaceProcesses {
    $escapedWorkspace = [regex]::Escape($Workspace)
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.CommandLine -and
            ($_.Name -in @("1cedt.exe", "javaw.exe", "java.exe")) -and
            ($_.CommandLine -match $escapedWorkspace)
        }
}

function Stop-EdtWorkspace {
    $processes = @(Get-EdtWorkspaceProcesses)
    if ($processes.Count -eq 0) {
        Write-Step "No EDT processes found for workspace $Workspace"
        return
    }

    Write-Step "Stopping EDT processes for workspace ${Workspace}: $($processes.ProcessId -join ', ')"
    if ($DryRun) {
        return
    }

    foreach ($cimProcess in $processes) {
        $process = Get-Process -Id $cimProcess.ProcessId -ErrorAction SilentlyContinue
        if ($process -and $process.MainWindowHandle -ne 0) {
            [void]$process.CloseMainWindow()
        }
    }

    $deadline = (Get-Date).AddSeconds($ShutdownTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        if (@(Get-EdtWorkspaceProcesses).Count -eq 0) {
            return
        }
    }

    $remaining = @(Get-EdtWorkspaceProcesses)
    if ($remaining.Count -eq 0) {
        return
    }

    if (-not $ForceKill) {
        throw "EDT did not close in $ShutdownTimeoutSec seconds. Re-run with -ForceKill or close it manually."
    }

    Write-Step "Force killing EDT processes: $($remaining.ProcessId -join ', ')"
    foreach ($cimProcess in $remaining) {
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

Set-Location -LiteralPath $ProjectRoot

$edtExe = Join-Path $EdtHome "1cedt.exe"
$repoZip = Join-Path $ProjectRoot "repositories\ru.xelgo.edt.contextlinks.repository\target\ru.xelgo.edt.contextlinks.repository.zip"
$profile = "C__Users_Xelgo_AppData_Local_1C_1cedtstart_installations_1C_EDT 2025.2_1cedt"
$bundlePool = Join-Path $env:USERPROFILE ".p2\pool"

if (-not (Test-Path -LiteralPath $edtExe)) {
    throw "EDT executable not found: $edtExe"
}

if (-not $SkipBuild) {
    $env:JAVA_HOME = $JavaHome
    Invoke-Step $Maven @("package", "-DskipTests")
}

if (-not (Test-Path -LiteralPath $repoZip)) {
    throw "Update site zip not found: $repoZip"
}

Stop-EdtWorkspace

$repoUri = "jar:file:/$(Convert-ToFileUriPath $repoZip)!/"
$directorArgs = @(
    "-nosplash",
    "-application", "org.eclipse.equinox.p2.director",
    "-repository", $repoUri,
    "-installIU", $InstallIU,
    "-profile", $profile,
    "-destination", $EdtHome,
    "-bundlepool", $bundlePool,
    "-p2.os", "win32",
    "-p2.ws", "win32",
    "-p2.arch", "x86_64",
    "-roaming",
    "-consoleLog"
)

Invoke-Step $edtExe $directorArgs $EdtHome
Clear-WorkspaceLog

if (-not $NoRestart) {
    $startArgs = @(
        "-data", $Workspace,
        "-vm", $LauncherVm,
        "--launcher.appendVmargs",
        "-vmargs",
        "-Djava.library.path=",
        "-Duser.language=ru",
        "-Xmx8192m"
    )

    Write-Step "Starting EDT for workspace $Workspace"
    if (-not $DryRun) {
        $argumentLine = ($startArgs | ForEach-Object { Format-Argument $_ }) -join " "
        Start-Process -FilePath $edtExe -ArgumentList $argumentLine -WorkingDirectory $EdtHome | Out-Null
    }
}
