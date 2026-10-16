<#
Moves completed downloads out of KioskUser's Downloads folder (locked
down to write-only for that account by Setup-DownloadHandoff.ps1) into a
folder only the operator/Administrators can read.

  - The next customer can never browse to a previous customer's download -
    KioskUser has no list/read access to its own Downloads folder,
    only create/write.
  - Nothing downloaded during a kiosk session is silently destroyed - it's
    handed off, not deleted (see why this replaces
    KioskDeleteDownloadsOnExit in the root README).

Runs as SYSTEM via the scheduled task that Setup-DownloadHandoff.ps1
registers (every minute), so SYSTEM's unrestricted access is what lets it
read files KioskUser itself cannot.
#>
param(
    [string]$DropBox = "C:\Users\KioskUser\Downloads",
    [string]$ForOperator = "C:\ProgramData\Kiosk\ForOperator",
    [string]$LogFile = "C:\ProgramData\Kiosk\handoff.log",
    [int]$RetentionDays = 30
)

if (-not (Test-Path $DropBox)) { return }
if (-not (Test-Path $ForOperator)) {
    New-Item -ItemType Directory -Path $ForOperator -Force | Out-Null
}

# .crdownload/.tmp/.partial = Chromium's in-progress download markers -
# skip anything still being written, it'll be picked up on a later run
# once Edge renames it to its final name.
$files = Get-ChildItem -Path $DropBox -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -notin ".crdownload", ".tmp", ".partial" }

foreach ($file in $files) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $destPath = Join-Path $ForOperator "${stamp}_$($file.Name)"
    $i = 1
    while (Test-Path $destPath) {
        $destPath = Join-Path $ForOperator "${stamp}_($i)_$($file.Name)"
        $i++
    }

    try {
        Move-Item -LiteralPath $file.FullName -Destination $destPath -ErrorAction Stop
        "$(Get-Date -Format s)  MOVED  $($file.Name) ($($file.Length) B) -> $destPath" |
            Add-Content -Path $LogFile -Encoding UTF8
    } catch {
        # Still locked/being written by Edge - leave it, retried next run.
    }
}

if ($RetentionDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    Get-ChildItem -Path $ForOperator -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        ForEach-Object {
            "$(Get-Date -Format s)  PURGED (retencja $RetentionDays dni, nieodebrane)  $($_.Name)" |
                Add-Content -Path $LogFile -Encoding UTF8
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
        }
}
