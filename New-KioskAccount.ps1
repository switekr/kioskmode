<#
Creates the local standard account that Assigned Access will lock into
Edge kiosk mode. Blank password is intentional: Windows only honors blank
passwords for console (local) logon by default, which matches the
Fast-User-Switching handover flow this kiosk is built around.

Run from an elevated (Administrator) PowerShell prompt.
#>
param(
    [string]$UserName = "KioskUser"
)

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)) {
    throw "Run this script from an elevated (Administrator) PowerShell prompt."
}

if (Get-LocalUser -Name $UserName -ErrorAction SilentlyContinue) {
    Write-Host "Account '$UserName' already exists - leaving it as is."
} else {
    New-LocalUser -Name $UserName `
        -NoPassword `
        -AccountNeverExpires `
        -PasswordNeverExpires `
        -FullName "Kiosk" `
        -Description "Restricted kiosk account, locked to Edge via Assigned Access" | Out-Null
    Add-LocalGroupMember -Group "Users" -Member $UserName -ErrorAction SilentlyContinue
    Write-Host "Created local standard account '$UserName' with no password."
}

Write-Host "Next: run Apply-KioskConfig.ps1 to lock this account into Edge kiosk mode."
