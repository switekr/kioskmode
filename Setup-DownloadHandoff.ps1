<#
One-time setup for secure handoff of files the customer downloads during
a kiosk session (bank statements, email attachments, ...):

  1. Locks down KioskUser's own Downloads folder so that account can
     create/write files there but cannot list, read, or delete anything -
     so a customer can never browse to what a previous customer
     downloaded. (Edge keeps using this folder as its default download
     location - nothing about Edge itself needs to change, which is what
     keeps this simple: no Group Policy / registry redirection, so it
     can't accidentally affect the operator's own Edge on the same
     machine the way an HKLM-wide DownloadDirectory policy would.)
  2. Registers a SYSTEM-run scheduled task that sweeps that folder every
     minute, moving each finished download into a folder only the
     operator/Administrators can read.

Requires KioskUser to have signed in at least once already (so
C:\Users\KioskUser\Downloads exists) - see step 4 in the root README.

Run from an elevated (Administrator) PowerShell prompt:
  .\Setup-DownloadHandoff.ps1                        # set up (defaults to current user as operator)
  .\Setup-DownloadHandoff.ps1 -OperatorAccount alice  # a specific operator account
  .\Setup-DownloadHandoff.ps1 -Remove                 # undo the automation (keeps any files already handed off)
#>
param(
    [string]$KioskUser = "KioskUser",
    [string]$OperatorAccount = $env:USERNAME,
    [string]$ForOperator = "C:\ProgramData\Kiosk\ForOperator",
    [switch]$Remove
)

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)) {
    throw "Run this script from an elevated (Administrator) PowerShell prompt."
}

$kioskDownloads = "C:\Users\$KioskUser\Downloads"
$taskName = "KioskDownloadHandoff"
$moverScript = Join-Path $PSScriptRoot "Move-KioskDownloads.ps1"

if ($Remove) {
    schtasks /Delete /TN $taskName /F 2>$null | Out-Null
    Write-Host "Zadanie '$taskName' usuniete."
    Write-Host "Uprawnienia $kioskDownloads NIE zostaly przywrocone automatycznie - zrob to recznie (icacls $kioskDownloads /reset), jesli to potrzebne."
    Write-Host "Pliki w $ForOperator NIE zostaly skasowane."
    return
}

if (-not (Test-Path $moverScript)) {
    throw "Nie znaleziono Move-KioskDownloads.ps1 obok tego skryptu ($PSScriptRoot)."
}

if (-not (Test-Path $kioskDownloads)) {
    throw "$kioskDownloads nie istnieje jeszcze - zaloguj sie raz na konto $KioskUser (tworzy to jego profil), zanim uruchomisz ten skrypt."
}

New-Item -ItemType Directory -Path $ForOperator -Force | Out-Null

# --- KioskUser's own Downloads folder: can create/append files (WD,AD)
#     but has no List Folder / Read Data / Delete on it - genuine
#     write-only "mailbox" semantics. This also means Edge itself,
#     running as KioskUser, can't re-open a file right after downloading
#     it (its own "open file" / preview from the download bar will fail) -
#     that's intentional: the customer shouldn't retain access to it once
#     it's dropped off.
icacls $kioskDownloads /inheritance:r | Out-Null
icacls $kioskDownloads /grant "SYSTEM:(OI)(CI)F" | Out-Null
icacls $kioskDownloads /grant "*S-1-5-32-544:(OI)(CI)F" | Out-Null   # BUILTIN\Administrators (well-known SID - resolves regardless of locale/domain)
icacls $kioskDownloads /grant "${KioskUser}:(OI)(CI)(WD,AD)" | Out-Null

# --- Handoff folder: only SYSTEM, Administrators, and the operator can
#     see it. Explicit Deny for KioskUser as belt-and-braces, since this
#     is where the actual handed-off files rest until picked up.
icacls $ForOperator /inheritance:r | Out-Null
icacls $ForOperator /grant "SYSTEM:(OI)(CI)F" | Out-Null
icacls $ForOperator /grant "*S-1-5-32-544:(OI)(CI)F" | Out-Null   # BUILTIN\Administrators
icacls $ForOperator /grant "${OperatorAccount}:(OI)(CI)M" | Out-Null
icacls $ForOperator /deny "${KioskUser}:(OI)(CI)F" | Out-Null

# --- SYSTEM-run scheduled task: sweep KioskUser's Downloads every minute.
#     SYSTEM's unrestricted access is what lets it read what KioskUser
#     itself can't.
$action = "-NoProfile -ExecutionPolicy Bypass -File `"$moverScript`" -DropBox `"$kioskDownloads`" -ForOperator `"$ForOperator`""
schtasks /Create /TN $taskName /TR "powershell.exe $action" /SC MINUTE /MO 1 /RU SYSTEM /RL HIGHEST /F | Out-Null

Write-Host "Gotowe."
Write-Host "  Downloads KioskUser (tylko zapis):        $kioskDownloads"
Write-Host "  Odbior dla operatora (${OperatorAccount}): $ForOperator"
Write-Host "  Log:                                       C:\ProgramData\Kiosk\handoff.log"
Write-Host "  Zadanie '$taskName' sprawdza folder co minute."
