param(
    [string]$Workspace = (Join-Path $env:LOCALAPPDATA "1C\1cedtstart\projects\EDT UH"),
    [int]$ProcessId = 0,
    [int]$DurationSec = 240,
    [int]$IntervalSec = 10,
    [int]$CommandTimeoutSec = 20,
    [string]$JcmdPath = "",
    [string]$OutputRoot = (Join-Path (Resolve-Path "$PSScriptRoot\..").Path "diagnostics")
)

$ErrorActionPreference = "Stop"

function Write-Step($Message) {
    Write-Host "==> $Message"
}

function Normalize-PathForCommandLine($Path) {
    return (Resolve-Path -LiteralPath $Path).Path
}

function Find-EdtJavaProcess($WorkspacePath) {
    $resolvedWorkspace = Normalize-PathForCommandLine $WorkspacePath
    $escapedWorkspace = [Regex]::Escape($resolvedWorkspace)
    $escapedWorkspaceSlash = [Regex]::Escape($resolvedWorkspace.Replace("\", "/"))

    $candidates = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @("1cedt.exe", "javaw.exe", "java.exe") -and
            $_.CommandLine -and
            ($_.CommandLine -match $escapedWorkspace -or $_.CommandLine -match $escapedWorkspaceSlash)
        } |
        Sort-Object CreationDate -Descending

    if (!$candidates) {
        throw "EDT Java process for workspace '$resolvedWorkspace' was not found."
    }

    return $candidates[0]
}

function Find-Jcmd($ExecutablePath) {
    $candidates = @()
    if ($JcmdPath) {
        $candidates += $JcmdPath
    }

    if ($ExecutablePath) {
        $executableBin = Split-Path -Parent $ExecutablePath
        $candidates += Join-Path $executableBin "jcmd.exe"
    }

    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME "bin\jcmd.exe"
    }

    $oneCComponents = "C:\Program Files\1C\1CE\components"
    if (Test-Path -LiteralPath $oneCComponents) {
        $candidates += Get-ChildItem -LiteralPath $oneCComponents -Directory -Filter "*jdk-full-17*" -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName "bin\jcmd.exe" }
    }

    $pathJcmd = Get-Command jcmd.exe -ErrorAction SilentlyContinue
    if ($pathJcmd) {
        $candidates += $pathJcmd.Source
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "jcmd.exe was not found. Pass -JcmdPath explicitly."
}

function Format-ProcessArgument($Argument) {
    if ($Argument -match '[\s"]') {
        return '"' + ($Argument -replace '"', '\"') + '"'
    }
    return $Argument
}

function Invoke-Jcmd($Jcmd, $TargetPid, [string[]]$Arguments, $OutputFile, $TimeoutSec) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Jcmd
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Arguments = ((@([string]$TargetPid) + $Arguments) | ForEach-Object { Format-ProcessArgument $_ }) -join " "

    $process = [System.Diagnostics.Process]::Start($psi)
    if (!$process.WaitForExit($TimeoutSec * 1000)) {
        try {
            $process.Kill()
        }
        catch {
            # Best-effort cleanup only.
        }
        "TIMEOUT after ${TimeoutSec}s: $Jcmd $TargetPid $($Arguments -join ' ')" | Set-Content -Path $OutputFile -Encoding UTF8
        return $false
    }

    $output = $process.StandardOutput.ReadToEnd()
    $errorText = $process.StandardError.ReadToEnd()
    ($output + $errorText) | Set-Content -Path $OutputFile -Encoding UTF8
    return $process.ExitCode -eq 0
}

$resolvedWorkspace = Normalize-PathForCommandLine $Workspace
$targetProcess = if ($ProcessId -gt 0) {
    Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
} else {
    Find-EdtJavaProcess $resolvedWorkspace
}

if (!$targetProcess) {
    throw "Process '$ProcessId' was not found."
}

$javaPath = $targetProcess.ExecutablePath
if (!$javaPath) {
    throw "Could not resolve Java executable path for process $($targetProcess.ProcessId)."
}

$jcmd = Find-Jcmd $javaPath

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$workspaceName = Split-Path -Leaf $resolvedWorkspace
$outputDir = Join-Path $OutputRoot ("$workspaceName-$($targetProcess.ProcessId)-$stamp")
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

Write-Step "Workspace: $resolvedWorkspace"
Write-Step "Process: $($targetProcess.ProcessId) $javaPath"
Write-Step "jcmd: $jcmd"
Write-Step "Output: $outputDir"

$metadataLog = Join-Path $resolvedWorkspace ".metadata\.log"
if (Test-Path -LiteralPath $metadataLog) {
    Copy-Item -LiteralPath $metadataLog -Destination (Join-Path $outputDir "workspace.log") -Force
}

$targetPid = [int]$targetProcess.ProcessId
Invoke-Jcmd $jcmd $targetPid @("VM.command_line") (Join-Path $outputDir "vm-command-line.txt") $CommandTimeoutSec | Out-Null
Invoke-Jcmd $jcmd $targetPid @("VM.system_properties") (Join-Path $outputDir "vm-system-properties.txt") $CommandTimeoutSec | Out-Null
Invoke-Jcmd $jcmd $targetPid @("GC.heap_info") (Join-Path $outputDir "gc-heap-info-before.txt") $CommandTimeoutSec | Out-Null

$jfrFile = Join-Path $outputDir "recording.jfr"
Invoke-Jcmd $jcmd $targetPid @(
    "JFR.start",
    "name=edt_extension_tweaks_capture",
    "settings=profile",
    "delay=1s",
    "duration=${DurationSec}s",
    "filename=$jfrFile",
    "dumponexit=true",
    "disk=true"
) (Join-Path $outputDir "jfr-start.txt") $CommandTimeoutSec | Out-Null

$samples = [Math]::Max(1, [Math]::Ceiling($DurationSec / [Math]::Max(1, $IntervalSec)))
$threadDumpTimeouts = 0
for ($i = 0; $i -lt $samples; $i++) {
    $threadDumpFile = Join-Path $outputDir ("thread-dump-{0:d2}.txt" -f $i)
    if ($threadDumpTimeouts -lt 2) {
        $threadDumpOk = Invoke-Jcmd $jcmd $targetPid @("Thread.print", "-l") $threadDumpFile $CommandTimeoutSec
        if (!$threadDumpOk) {
            $threadDumpTimeouts++
        }
    } else {
        "Skipped after two consecutive jcmd Thread.print failures." | Set-Content -Path $threadDumpFile -Encoding UTF8
    }

    if ($i -lt $samples - 1) {
        Start-Sleep -Seconds $IntervalSec
    }
}

Invoke-Jcmd $jcmd $targetPid @("GC.heap_info") (Join-Path $outputDir "gc-heap-info-after.txt") $CommandTimeoutSec | Out-Null
Invoke-Jcmd $jcmd $targetPid @("JFR.check", "name=edt_extension_tweaks_capture") (Join-Path $outputDir "jfr-check.txt") $CommandTimeoutSec | Out-Null

Get-ChildItem -LiteralPath $outputDir | Select-Object Name, Length, LastWriteTime
