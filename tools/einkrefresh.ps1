<#
.SYNOPSIS
    Experiment with the InkReader 6's e-ink refresh mode and force-refresh hook.

.DESCRIPTION
    Two device levers were found by inspecting the ROM:

      persist.sys.mRefreshMode   integer, default 2 on this device.
                                 WRITABLE from adb shell (uid 2000) -- verified.
                                 Referenced by SystemUI.vdex.
      persist.sys.canRefresh     integer, default 0. Also writable. Purpose unconfirmed.
      android.eink.force.refresh broadcast. This is what the status-bar's tap-to-refresh
                                 arrow sends. Unprotected, so any caller can send it.

    IMPORTANT -- what is NOT known:
      The property store accepts ANY integer, so "it wrote fine" does not mean the value
      is a real mode. The value->waveform mapping is not recoverable without decompiling
      SystemUI/framework, and this device is not rooted. Only your eyes on the panel can
      tell which values do anything. That is what `try` is for.

      It is also unconfirmed whether a change applies live or only after reboot. If a mode
      seems to do nothing, reboot and re-check before concluding it is inert.

    Background -- why the ceiling cannot move:
      The panel reports a fixed 16.0 fps mode and exposes no alternative:
        supportedModes [{id=1, width=758, height=1024, fps=16.0}]
      That is the hardware/driver refresh ceiling and no property changes it. What CAN
      change is the WAVEFORM used per update -- the real determinant of perceived speed.
      Faster waveforms trade grayscale depth and ghosting for latency, which is exactly
      the tradeoff Boox-style "fast / ultrafast" modes make.

.EXAMPLE
    .\einkrefresh.ps1 get
    .\einkrefresh.ps1 try              # step 0..4, judge each by eye
    .\einkrefresh.ps1 set -Mode 3
    .\einkrefresh.ps1 flush
    .\einkrefresh.ps1 reset
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('get','set','try','flush','reset','bench')]
    [string]$Command = 'get',

    [int]$Mode = -1,
    [int]$CanRefresh = -1,
    [int]$Max = 4,
    [int]$PauseSeconds = 12
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

# The device's factory value, captured before any of our changes.
$DefaultMode = 2
$DefaultCanRefresh = 0

function Get-Mode        { (& $Adb shell getprop persist.sys.mRefreshMode).Trim() }
function Get-CanRefresh  { (& $Adb shell getprop persist.sys.canRefresh).Trim() }

function Send-Flush {
    & $Adb shell am broadcast -a android.eink.force.refresh 2>&1 | Out-Null
}

function Show-State {
    '{0,-28} {1}' -f 'persist.sys.mRefreshMode', (Get-Mode)
    '{0,-28} {1}' -f 'persist.sys.canRefresh',   (Get-CanRefresh)
    '{0,-28} {1}' -f 'factory defaults',         "mRefreshMode=$DefaultMode canRefresh=$DefaultCanRefresh"
    ''
    'Panel mode (fixed, not changeable):'
    $line = (& $Adb shell dumpsys display 2>$null | Select-String 'supportedModes' | Select-Object -First 1)
    if ($line) {
        if ("$line" -match '(\{id=\d+[^}]*\})') { "  $($Matches[1])" } else { "  $line" }
    }
}

switch ($Command) {

'get' { Show-State }

'flush' {
    Send-Flush
    Write-Host 'sent android.eink.force.refresh'
    Write-Host 'Watch the panel: a full flush inverts briefly and clears ghosting.' -ForegroundColor DarkGray
    Write-Host 'If nothing visibly happened, the broadcast is likely system-only in practice' -ForegroundColor DarkGray
    Write-Host 'and the app must fall back to the invert-and-restore trick (Plan.md 3.4).' -ForegroundColor DarkGray
}

'set' {
    if ($Mode -ge 0) {
        & $Adb shell setprop persist.sys.mRefreshMode $Mode 2>&1 | Out-Null
        Write-Host "mRefreshMode -> $(Get-Mode)"
    }
    if ($CanRefresh -ge 0) {
        & $Adb shell setprop persist.sys.canRefresh $CanRefresh 2>&1 | Out-Null
        Write-Host "canRefresh -> $(Get-CanRefresh)"
    }
    if ($Mode -lt 0 -and $CanRefresh -lt 0) { throw 'pass -Mode n and/or -CanRefresh n' }
    Send-Flush
}

'try' {
    # Only a human looking at the panel can evaluate this, so drive it interactively.
    Write-Host ''
    Write-Host 'Stepping through refresh modes. For each one, on the DEVICE:' -ForegroundColor Cyan
    Write-Host '  scroll a page, open an app, type a little -- then judge:' -ForegroundColor Cyan
    Write-Host '    * faster or slower than before?' -ForegroundColor Cyan
    Write-Host '    * more or less ghosting (leftover text from the previous screen)?' -ForegroundColor Cyan
    Write-Host '    * any visual corruption?' -ForegroundColor Cyan
    Write-Host ''
    Write-Host "Original value was $DefaultMode; it is restored at the end." -ForegroundColor DarkGray
    Write-Host ''
    for ($m = 0; $m -le $Max; $m++) {
        & $Adb shell setprop persist.sys.mRefreshMode $m 2>&1 | Out-Null
        Send-Flush
        Write-Host ("--- mRefreshMode = {0} (reads {1}) --- {2}s to evaluate" -f $m, (Get-Mode), $PauseSeconds) -ForegroundColor Yellow
        Start-Sleep -Seconds $PauseSeconds
    }
    & $Adb shell setprop persist.sys.mRefreshMode $DefaultMode 2>&1 | Out-Null
    Send-Flush
    Write-Host ''
    Write-Host "restored mRefreshMode = $(Get-Mode)" -ForegroundColor Green
    Write-Host 'Pick the best one with:  .\einkrefresh.ps1 set -Mode <n>'
    Write-Host 'If none differed, reboot and retry -- the value may only be read at boot.' -ForegroundColor DarkGray
}

'bench' {
    # Crude but real: time how long N full screencaps take. This measures the
    # capture+pipeline cost, NOT panel latency, so treat it as a rough relative
    # signal between modes only -- never as an absolute refresh figure.
    Write-Host 'Rough relative timing (NOT true panel latency). 10 screencaps per mode.'
    Write-Host ''
    foreach ($m in 0..$Max) {
        & $Adb shell setprop persist.sys.mRefreshMode $m 2>&1 | Out-Null
        Send-Flush
        Start-Sleep -Seconds 2
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        for ($i = 0; $i -lt 10; $i++) {
            & $Adb shell screencap -p /sdcard/.bench.png 2>&1 | Out-Null
        }
        $sw.Stop()
        '  mode {0}: {1,6:N0} ms / 10 caps' -f $m, $sw.ElapsedMilliseconds
    }
    & $Adb shell rm -f /sdcard/.bench.png 2>&1 | Out-Null
    & $Adb shell setprop persist.sys.mRefreshMode $DefaultMode 2>&1 | Out-Null
    Write-Host ''
    Write-Host "restored mRefreshMode = $(Get-Mode)" -ForegroundColor Green
}

'reset' {
    & $Adb shell setprop persist.sys.mRefreshMode $DefaultMode 2>&1 | Out-Null
    & $Adb shell setprop persist.sys.canRefresh   $DefaultCanRefresh 2>&1 | Out-Null
    Send-Flush
    Write-Host 'restored factory values:'
    Show-State
}

}
