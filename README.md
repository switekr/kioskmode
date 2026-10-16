# Kiosk browser without logging out the current user

Goal: hand the device to a customer to check email/banking in a locked-down
Edge window, without ending your own signed-in session, and without letting
the customer reach any other window or app on the machine.

Two independent approaches live in this repo:

- **This one** (files below) - Windows Assigned Access, targeting a
  dedicated `KioskUser` account. Native Windows feature, requires
  Enterprise/Education edition, no code to maintain.
- **[KioskLauncher/](KioskLauncher/)** - a small C# app using the same
  desktop-isolation primitive behind the secure UAC prompt
  (`CreateDesktop`/`SwitchDesktop`). No second account, no Enterprise
  requirement, instant switch via hotkey instead of a sign-in. See
  [KioskLauncher/README.md](KioskLauncher/README.md).
- **[JKioskLauncher/](JKioskLauncher/)** - the same approach ported to
  Java/JNA. Untested (no JDK on the machine it was written on) - see the
  warning at the top of [JKioskLauncher/README.md](JKioskLauncher/README.md)
  before relying on it.

## Why this works

Windows desktop sessions are isolated per signed-in user. If the customer
uses a *different account* than you, their Alt+Tab, taskbar, and Win+Tab can
only ever see windows that belong to their own session - your open apps
simply don't exist from their side, kiosk restrictions or not. Fast User
Switching lets you swap to that account and back without signing either one
out, so your working session just keeps running in the background, locked.

On top of that isolation, Windows Enterprise's **Assigned Access** feature
locks the customer's account into a single app (Edge, in kiosk mode) so they
can't even reach their own desktop, taskbar, or Start menu.

## Files here

- `New-KioskAccount.ps1` - creates the local `KioskUser` standard account.
- `KioskConfig.xml` - the Assigned Access profile: locks `KioskUser` into
  Edge, launched with `--kiosk` in **public-browsing** mode (multi-tab,
  InPrivate, no downloads/history retained, auto-resets after 5 min idle).
- `Apply-KioskConfig.ps1` - applies (or `-Remove`s) that XML via the local
  MDM Bridge WMI Provider, using a throwaway SYSTEM scheduled task (no
  third-party tools needed).
- `Setup-DownloadHandoff.ps1` / `Move-KioskDownloads.ps1` - securely hands
  off anything the customer downloads (bank statements, attachments) to
  the operator instead of deleting it or leaving it reachable by the next
  customer. See "Downloaded attachments" below.

## Deploy (one-time setup)

1. Confirm where Edge is actually installed on this machine - the XML
   assumes the common default, but newer installs can land in `Program
   Files` instead of `Program Files (x86)`:

   ```powershell
   Test-Path "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
   Test-Path "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
   ```

   Edit `v4:ClassicAppPath` in `KioskConfig.xml` if the second path is the
   one that exists instead.

2. Optionally edit the start URL and idle timeout in
   `KioskConfig.xml` (`--kiosk <url>` and `--kiosk-idle-timeout-minutes=`).

3. From an elevated PowerShell prompt:

   ```powershell
   .\New-KioskAccount.ps1
   .\Apply-KioskConfig.ps1
   ```

   `Apply-KioskConfig.ps1` prints `OK: ...` on success. The configuration
   takes effect the next time `KioskUser` signs in.

4. Test it once: sign out (fully sign out, just for this test) and sign
   in as `KioskUser` - it should go straight into full-screen Edge with no
   address bar, no way to reach the desktop. Ctrl+Alt+Del breaks out to the
   secure Windows screen for that account (lock/switch user/task manager);
   it never exposes your session. Then switch back to your own account.

## Day-to-day handover

1. Press **Win+L**, then click **Switch user** (do *not* click Sign out).
2. Pick `KioskUser`. It has no password, so it signs straight in and
   launches the kiosk browser.
3. Hand the device to the customer. They're confined to Edge in that
   session; your session is untouched, just locked, in the background.
4. When they're done: Ctrl+Alt+Del → switch back to your account.
   (Optionally trigger another idle reset by leaving `KioskUser` untouched
   for the idle timeout - Edge clears the InPrivate session automatically.)

## Removing / undoing

```powershell
.\Apply-KioskConfig.ps1 -Remove
```

This clears the Assigned Access policy (takes effect on `KioskUser`'s next
sign-in). It won't undo Start menu state Windows may have cached for that
account, which doesn't matter here since the account is single-purpose.

## Downloaded attachments

A customer checking email/banking will sometimes download something - a
statement, an attachment. Two requirements to satisfy at once: the next
customer must never be able to see it, but it shouldn't just be destroyed
either - the operator needs it handed off somewhere safe.

`Setup-DownloadHandoff.ps1` sets this up:

1. Locks down `KioskUser`'s own Downloads folder with NTFS permissions so
   that account can create/write files there but cannot list, read, or
   delete anything in it - genuine write-only "mailbox" semantics. A
   customer (or Edge itself, running as `KioskUser`) can't browse to, open,
   or even re-open a file right after downloading it. Nothing about Edge's
   configuration changes - it keeps downloading to its normal default
   location, which is what keeps this simple and keeps it scoped to only
   `KioskUser`: no Group Policy/registry redirection that could accidentally
   affect the operator's own Edge on the same machine.
2. Registers a SYSTEM-run scheduled task (`Move-KioskDownloads.ps1`, every
   minute) that sweeps that folder and moves each finished download into
   `C:\ProgramData\Kiosk\ForOperator` - readable only by
   `Administrators` and whichever account you name as `-OperatorAccount`
   (defaults to whoever runs the setup script). SYSTEM's unrestricted
   access is what lets the task read files `KioskUser` itself cannot.
   Moves are logged to `C:\ProgramData\Kiosk\handoff.log`; files older than
   30 days (configurable) are purged if nobody picked them up.

Run once, after `KioskUser` has signed in at least once (so its Downloads
folder exists):

```powershell
.\Setup-DownloadHandoff.ps1
```

To undo (keeps any files already handed off):

```powershell
.\Setup-DownloadHandoff.ps1 -Remove
```

Note on locale: built-in group names like "Administrators" don't resolve
by plain name on non-English Windows (e.g. it's "Administratorzy" on
Polish installs) - the script uses the well-known SID (`*S-1-5-32-544`)
instead, which works regardless of display language.

## Optional hardening

Set these via Group Policy (`gpedit.msc` → Computer Configuration →
Administrative Templates → Microsoft Edge → Kiosk mode) if you want to go
further than the defaults in `KioskConfig.xml`:

- `URLAllowlist` / `URLBlocklist` - restrict navigation to specific sites
  (e.g. only the bank's domain and a webmail provider).
- `Enumerate local users on domain-joined computers` (Computer
  Configuration → Administrative Templates → System → Logon) - only needed
  if this PC is domain/Entra-joined and `KioskUser` doesn't show up on the
  sign-in/switch-user screen.

Deliberately **not** listed here: `KioskDeleteDownloadsOnExit`. It wipes
downloads the moment the kiosk session resets, which races against (and
can defeat) the handoff described above - use the drop-box approach
instead of this policy, not alongside it.
