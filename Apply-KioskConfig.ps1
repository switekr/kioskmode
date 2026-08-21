<#
Applies (or, with -Remove, clears) the Assigned Access configuration in
KioskConfig.xml via the local MDM Bridge WMI Provider. That provider only
accepts calls from the SYSTEM account, so this script schedules a one-shot
SYSTEM task to do the actual write, waits for it, then deletes the task -
no third-party tools (e.g. psexec) required.

Run from an elevated (Administrator) PowerShell prompt:
  .\Apply-KioskConfig.ps1            # apply KioskConfig.xml
  .\Apply-KioskConfig.ps1 -Remove    # remove the assigned access config
#>
param(
    [switch]$Remove,
    [string]$ConfigPath = (Join-Path $PSScriptRoot "KioskConfig.xml"),
    [switch]$AsSystem   # internal flag: set when re-invoked by the SYSTEM task
)

$logFile = Join-Path $env:TEMP "kiosk-assignedaccess.log"

function Set-AssignedAccessConfiguration {
    param([string]$Xml)
    $obj = Get-CimInstance -Namespace "root\cimv2\mdm\dmmap" -ClassName "MDM_AssignedAccess"
    $obj.Configuration = if ($Xml) { [System.Net.WebUtility]::HtmlEncode($Xml) } else { $null }
    Set-CimInstance -CimInstance $obj -ErrorAction Stop
}

if ($AsSystem) {
    try {
        if ($Remove) {
            Set-AssignedAccessConfiguration -Xml $null
            "OK: Assigned Access configuration removed." | Out-File $logFile
        } else {
            $xmlContent = Get-Content -Raw -Path $ConfigPath
            Set-AssignedAccessConfiguration -Xml $xmlContent
            "OK: Assigned Access configuration applied from $ConfigPath" | Out-File $logFile
        }
    } catch {
        "ERROR: $($_.Exception.Message)" | Out-File $logFile
    }
    return
}

# --- Interactive, elevated entry point ---
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)) {
    throw "Run this script from an elevated (Administrator) PowerShell prompt."
}

if (-not $Remove -and -not (Test-Path $ConfigPath)) {
    throw "Config file not found: $ConfigPath"
}

if (Test-Path $logFile) { Remove-Item $logFile -Force }

$taskName = "ApplyKioskAssignedAccess"
$argList = "-NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Path)`" -AsSystem -ConfigPath `"$ConfigPath`""
if ($Remove) { $argList += " -Remove" }

schtasks /Create /TN $taskName /TR "powershell.exe $argList" /SC ONCE /ST 00:00 /RU SYSTEM /RL HIGHEST /F | Out-Null
schtasks /Run /TN $taskName | Out-Null

$timer = [Diagnostics.Stopwatch]::StartNew()
while (-not (Test-Path $logFile) -and $timer.Elapsed.TotalSeconds -lt 30) {
    Start-Sleep -Milliseconds 500
}
schtasks /Delete /TN $taskName /F | Out-Null

if (Test-Path $logFile) {
    Get-Content $logFile
} else {
    Write-Warning "Timed out waiting for the SYSTEM task to report a result."
}
