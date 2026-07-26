<#
.SYNOPSIS
    Undo the de-Sinicisation applied to the InkReader 6 on 2026-07-25.

.DESCRIPTION
    The device locale was ALREADY en-US (persist.sys.locale=en-US). The Chinese text
    came from OEM apps shipping zh-only strings, so the fix was removing those apps --
    no firmware flash, no root.

    Applied changes:
      Uninstalled (user apps, APKs backed up to tools/apk-backup/):
        com.jd.app.reader              京东读书
        com.netease.snailread.ink      网易蜗牛读书
        com.moan.cloudservices         墨案助手
      Disabled (system apps, still on /system, just hidden from the user):
        com.moan.appstore              Chinese app store
        com.moan.browser
        com.moan.sdmanage
        com.duokan.einkreader          多看
        com.zhangyue.read.iReader.eink 掌阅精选

      MUST STAY ENABLED -- com.moan.launcher:
        Disabling it makes the status-bar home button crash SystemUI. The OEM
        hardwired that button to an EXPLICIT component instead of the HOME intent:
          ActivityNotFoundException: Unable to find explicit activity class
            {com.moan.launcher/com.moan.launcher.MainActivity}
            at PhoneStatusBarTransitions$2.onClick(PhoneStatusBarTransitions.java:240)
        Re-enabled 2026-07-25 for this reason. Unlauncher remains the default HOME,
        so the PHYSICAL home key (sunxi-keyboard key 102) still goes to Unlauncher;
        only the on-screen status-bar arrow lands on the OEM launcher.

      Keyboard swapped (2026-07-25, after Simple Keyboard 5.28 was installed):
        rkr.simplekeyboard.inputmethod/.latin.LatinIME  enabled + set as default
        com.sohu.inputmethod.sogou.oem                  IME disabled, package disabled

    Deliberately NOT touched:
        com.moan.floatball -- OEM floating-ball app. Left enabled as a reference
            implementation for InkDeck's floating menu (Plan.md 6).

.NOTES
    KNOWN PRE-EXISTING BUG, not caused by any of the above:
    The main Settings dashboard (android.settings.SETTINGS) crashes with
        NullPointerException at SettingsActivity.onCreate(SettingsActivity.java:359)
        -> View.setVisibility(int) on a null object reference
    Confirmed pre-existing: it still crashes with every OEM package re-enabled, and
    `pm clear com.android.settings` does not fix it. It is a defect in this ROM's
    modified Settings APK and cannot be patched without root.
    Workaround: launch Settings SUB-PAGES directly by intent -- they all work.
    Use `.\restore-oem-apps.ps1 -What settings` for the list.

.EXAMPLE
    .\restore-oem-apps.ps1 -What settings     # working Settings sub-page intents
    .\restore-oem-apps.ps1 -What keyboard     # restore Sogou as an IME
    .\restore-oem-apps.ps1 -What disabled     # re-enable the system apps
    .\restore-oem-apps.ps1 -What uninstalled  # reinstall from tools/apk-backup/
    .\restore-oem-apps.ps1 -What all
#>
[CmdletBinding()]
param(
    [ValidateSet('disabled','uninstalled','keyboard','settings','all','status')]
    [string]$What = 'status'
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$AdbCandidates = @(
    $env:EINKNAV_ADB,
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe"
) | Where-Object { $_ }
$Adb = $null
foreach ($c in $AdbCandidates) { if (Test-Path $c) { $Adb = $c; break } }
if (-not $Adb) { throw "adb.exe not found. Set `$env:EINKNAV_ADB." }

$BackupDir = Join-Path $PSScriptRoot 'apk-backup'

     # com.moan.launcher deliberately NOT listed -- it must stay enabled or the
     # status-bar home button crashes SystemUI. See .NOTES.
$Disabled = @(
    'com.moan.appstore',
    'com.moan.browser',
    'com.moan.sdmanage',
    'com.duokan.einkreader',
    'com.zhangyue.read.iReader.eink'
)
$Uninstalled = @(
    'com.jd.app.reader',
    'com.netease.snailread.ink',
    'com.moan.cloudservices'
)

function Show-Status {
    Write-Host 'Disabled system apps:' -ForegroundColor Cyan
    foreach ($p in $Disabled) {
        $enabled = & $Adb shell "pm list packages -e $p" 2>$null
        $state = if ($enabled -match [regex]::Escape($p)) { 'ENABLED' } else { 'disabled' }
        '  {0,-40} {1}' -f $p, $state
    }
    Write-Host ''
    Write-Host 'Uninstalled user apps:' -ForegroundColor Cyan
    foreach ($p in $Uninstalled) {
        $present = & $Adb shell "pm list packages $p" 2>$null
        $state = if ($present -match [regex]::Escape($p)) { 'INSTALLED' } else { 'removed' }
        $apk = Join-Path $BackupDir "$p.apk"
        $hasBak = if (Test-Path $apk) { 'backup present' } else { 'NO BACKUP' }
        '  {0,-32} {1,-10} {2}' -f $p, $state, $hasBak
    }
    Write-Host ''
    Write-Host 'Keyboards (must never be empty):' -ForegroundColor Cyan
    & $Adb shell ime list -s 2>&1 | ForEach-Object { "  $_" }
}

switch ($What) {

    'status' { Show-Status }

    'disabled' {
        foreach ($p in $Disabled) {
            $r = & $Adb shell pm enable --user 0 $p 2>&1
            '{0,-40} {1}' -f $p, (($r -join ' ').Trim())
        }
        Write-Host ''
        Write-Warning 'Re-enabling com.moan.launcher may reclaim the HOME intent. If the Chinese launcher returns, set Unlauncher as default again in Settings > Apps > Default apps.'
    }

    'uninstalled' {
        foreach ($p in $Uninstalled) {
            $apk = Join-Path $BackupDir "$p.apk"
            if (-not (Test-Path $apk)) { Write-Warning "no backup for $p - skipped"; continue }
            $r = & $Adb install -r $apk 2>&1
            '{0,-32} {1}' -f $p, (($r | Select-Object -Last 1).ToString().Trim())
        }
    }

    'keyboard' {
        # Restores Sogou as an available IME. Simple Keyboard stays the default;
        # this only puts the fallback back in the list.
        & $Adb shell pm enable --user 0 com.sohu.inputmethod.sogou.oem 2>&1
        & $Adb shell ime enable com.sohu.inputmethod.sogou.oem/.SogouIME 2>&1
        Write-Host ''
        Write-Host 'Enabled IMEs now:' -ForegroundColor Cyan
        & $Adb shell ime list -s 2>&1 | ForEach-Object { "  $_" }
        Write-Host ''
        Write-Host 'To make Sogou default again:' -ForegroundColor DarkGray
        Write-Host '  adb shell ime set com.sohu.inputmethod.sogou.oem/.SogouIME' -ForegroundColor DarkGray
    }

    'settings' {
        # The main Settings dashboard crashes (pre-existing ROM bug, see .NOTES).
        # Every sub-page below was verified to launch correctly on this device.
        $pages = [ordered]@{
            'Keyboards / input methods' = 'android.settings.INPUT_METHOD_SETTINGS'
            'Language (locale picker)'  = 'android.settings.LOCALE_SETTINGS'
            'Wi-Fi'                     = 'android.settings.WIFI_SETTINGS'
            'Bluetooth'                 = 'android.settings.BLUETOOTH_SETTINGS'
            'Display'                   = 'android.settings.DISPLAY_SETTINGS'
            'Sound'                     = 'android.settings.SOUND_SETTINGS'
            'Apps list'                 = 'android.settings.APPLICATION_SETTINGS'
            'All apps (manage)'         = 'android.settings.MANAGE_APPLICATIONS_SETTINGS'
            'Battery'                   = 'android.intent.action.POWER_USAGE_SUMMARY'
            'Storage'                   = 'android.settings.INTERNAL_STORAGE_SETTINGS'
            'Date & time'               = 'android.settings.DATE_SETTINGS'
            'Security'                  = 'android.settings.SECURITY_SETTINGS'
            'Accessibility'             = 'android.settings.ACCESSIBILITY_SETTINGS'
            'Device info'               = 'android.settings.DEVICE_INFO_SETTINGS'
            'Overlay permission'        = 'android.settings.action.MANAGE_OVERLAY_PERMISSION'
        }
        Write-Host 'Main Settings crashes on this ROM. Launch sub-pages directly:' -ForegroundColor Yellow
        Write-Host ''
        foreach ($k in $pages.Keys) {
            '  {0,-28} adb shell am start -a {1}' -f $k, $pages[$k]
        }
        # APPLICATION_DEVELOPMENT_SETTINGS is not handled by this ROM (it falls through
        # to the launcher); the explicit component works instead.
        '  {0,-28} adb shell am start -n com.android.settings/.DevelopmentSettings' -f 'Developer options'
        Write-Host ''
        Write-Host 'Tip: install an activity-launcher app from F-Droid to reach these' -ForegroundColor DarkGray
        Write-Host '     from the device itself without a PC.' -ForegroundColor DarkGray
    }

    'all' {
        & $PSCommandPath -What disabled
        & $PSCommandPath -What uninstalled
        & $PSCommandPath -What keyboard
    }
}
