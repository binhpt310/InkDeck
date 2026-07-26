<#
.SYNOPSIS
    einknav - UI/UX inspection + navigation harness for the InkReader 6 (EPD106A) e-ink device.

.DESCRIPTION
    Wraps adb into a screen-navigation loop usable by a human or an agent:
      look   -> pull a PNG screenshot (binary-safe)
      tree   -> pull the uiautomator XML hierarchy (text + bounds, works even when
                screencap returns a blank framebuffer)
      probe  -> flat list of on-screen text + tap centres, derived from `tree`
      tap / swipe / text / key / back / home  -> input injection
      focus  -> currently focused activity
      watch  -> capture screenshot+tree pairs on an interval
      rotate -> set display rotation
      info   -> device baseline facts

    Verified working on: EPD106A (InkReader 6), Android 8.1.0 / API 27,
    758x1024 @212dpi, Allwinner sun8iw15p1, non-rooted, adb over USB (MTP mode).

.EXAMPLE
    .\einknav.ps1 info
    .\einknav.ps1 look -Open
    .\einknav.ps1 probe
    .\einknav.ps1 tap -X 379 -Y 968
    .\einknav.ps1 launch -Package info.plateaukao.einkbro
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('info','look','tree','probe','tap','swipe','text','key','back','home',
                 'focus','watch','rotate','launch','apps','shot-series','help')]
    [string]$Command = 'help',

    [int]$X, [int]$Y,
    [int]$X2, [int]$Y2,
    [int]$Duration = 300,
    [string]$Value,
    [string]$Package,
    [int]$Rotation = 0,
    [int]$Count = 5,
    [int]$IntervalMs = 1500,
    [string]$OutDir,
    [switch]$Open,
    [switch]$Raw
)

$ErrorActionPreference = 'Stop'

# Much of this device's stock UI is Simplified Chinese; without this the console
# renders uiautomator text as mojibake even though the XML itself is fine.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$OutputEncoding = [System.Text.Encoding]::UTF8

# ---------------------------------------------------------------- setup ----

$AdbCandidates = @(
    $env:EINKNAV_ADB,
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
) | Where-Object { $_ }

$Adb = $null
foreach ($c in $AdbCandidates) { if (Test-Path $c) { $Adb = $c; break } }
if (-not $Adb) {
    $inPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($inPath) { $Adb = $inPath.Source }
}
if (-not $Adb) { throw "adb.exe not found. Set `$env:EINKNAV_ADB to its full path." }

if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot '..\.einknav' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

# Device-side scratch path; cleaned after every pull so we never leave litter.
$DevTmp = '/sdcard/.einknav'

function Adb { & $Adb @args }

function Adb-Quiet {
    # Native stderr in PowerShell 5.1 becomes an ErrorRecord; swallow it.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Adb @args 2>&1 | Out-String } finally { $ErrorActionPreference = $old }
}

function Assert-Device {
    $out = Adb-Quiet devices
    # @() matters: a single match returns a scalar string, and indexing [0] on a
    # string yields its first character instead of the line.
    $lines = @($out -split "`r?`n" | Where-Object { $_ -match '\sdevice(\s|$)' })
    if ($lines.Count -eq 0) {
        throw "No adb device in 'device' state. Check USB cable and that the tablet is in MTP mode."
    }
    return ($lines[0] -split '\s+')[0]
}

function Assert-Awake {
    # A dozing panel breaks BOTH channels, and neither says so:
    #   uiautomator -> "ERROR: null root node returned by UiTestAutomationBridge."
    #   screencap   -> a PNG that is entirely black
    # The all-black screenshot noted in Plan.md 1.1 was almost certainly this. Wake first
    # and the same commands work, so do it here rather than leaving it as folklore.
    $power = Adb-Quiet shell "dumpsys power | grep -m1 mWakefulness="
    if ($power -match 'mWakefulness=(\w+)') {
        if ($Matches[1] -ne 'Awake') {
            Write-Host "device was $($Matches[1]); waking" -ForegroundColor DarkGray
            Adb-Quiet shell input keyevent KEYCODE_WAKEUP | Out-Null
            Start-Sleep -Milliseconds 1200
            $power = Adb-Quiet shell "dumpsys power | grep -m1 mWakefulness="
            if ($power -notmatch 'mWakefulness=Awake') {
                Write-Warning "device is still not awake; probe/look may return nothing usable."
            }
        }
    }
}

function New-Stamp {
    # Date.now() is fine here (plain script, not a workflow); keeps filenames sortable.
    return (Get-Date).ToString('yyyyMMdd-HHmmss-fff')
}

# ---------------------------------------------------------- primitives ----

function Get-Screenshot {
    param([string]$Path)
    # screencap MUST be written device-side then pulled. Piping `exec-out screencap`
    # through PowerShell's `>` corrupts the PNG (BOM + text re-encoding).
    Adb shell mkdir -p $DevTmp | Out-Null
    Adb-Quiet shell screencap -p "$DevTmp/s.png" | Out-Null
    Adb-Quiet pull "$DevTmp/s.png" "$Path" | Out-Null
    Adb-Quiet shell rm -f "$DevTmp/s.png" | Out-Null
    if (-not (Test-Path $Path)) { throw "screenshot pull failed" }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 8 -or $bytes[0] -ne 0x89 -or $bytes[1] -ne 0x50) {
        throw "pulled file is not a valid PNG (first bytes: $($bytes[0..3]))"
    }
    return $Path
}

function Get-UiTree {
    param([string]$Path)
    Adb shell mkdir -p $DevTmp | Out-Null
    $dump = Adb-Quiet shell uiautomator dump $DevTmp/ui.xml
    if ($dump -notmatch 'dumped to') { Write-Warning "uiautomator: $dump" }
    Adb-Quiet pull "$DevTmp/ui.xml" "$Path" | Out-Null
    Adb-Quiet shell rm -f "$DevTmp/ui.xml" | Out-Null
    if (-not (Test-Path $Path)) { throw "ui.xml pull failed" }
    return $Path
}

function Walk-UiNode {
    # Real function, not a scriptblock closure: `+=` against a captured variable
    # inside a nested scriptblock fails on PS 5.1 (op_Addition on PSObject).
    param($Node, [int]$Depth, [System.Collections.ArrayList]$Sink)
    foreach ($child in $Node.ChildNodes) {
        if ($child.NodeType -ne 'Element') { continue }
        $b = [string]$child.bounds
        $cx = $null; $cy = $null; $w = $null; $h = $null
        if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
            $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
            $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
            $w = $x2 - $x1; $h = $y2 - $y1
        }
        $txt  = [string]$child.text
        $desc = [string]$child.'content-desc'
        $rid  = [string]$child.'resource-id'
        # Keep nodes that carry meaning: text, a11y label, an id, or tappability.
        if ($txt -or $desc -or $rid -or ([string]$child.clickable -eq 'true')) {
            [void]$Sink.Add([pscustomobject]@{
                Depth      = $Depth
                Text       = $txt
                Desc       = $desc
                Id         = ($rid -replace '^.*/', '')
                Class      = ([string]$child.class -replace '^.*\.', '')
                Clickable  = ([string]$child.clickable  -eq 'true')
                Scrollable = ([string]$child.scrollable -eq 'true')
                TapX       = $cx
                TapY       = $cy
                W          = $w
                H          = $h
                Bounds     = $b
            })
        }
        Walk-UiNode -Node $child -Depth ($Depth + 1) -Sink $Sink
    }
}

function Parse-UiTree {
    param([string]$Path)
    [xml]$xml = Get-Content $Path -Raw
    $sink = New-Object System.Collections.ArrayList
    Walk-UiNode -Node $xml.hierarchy -Depth 0 -Sink $sink
    return $sink
}

# ------------------------------------------------------------ commands ----

switch ($Command) {

'info' {
    $serial = Assert-Device
    $props = @{
        'Serial'        = $serial
        'BT name'       = (Adb shell getprop ro.mtp.bt.name)
        'Model'         = (Adb shell getprop ro.product.model)
        'Manufacturer'  = (Adb shell getprop ro.product.manufacturer)
        'Board'         = (Adb shell getprop ro.board.platform)
        'ABI'           = (Adb shell getprop ro.product.cpu.abi)
        'Android'       = (Adb shell getprop ro.build.version.release)
        'API level'     = (Adb shell getprop ro.build.version.sdk)
        'Firmware'      = (Adb shell getprop ro.project.sw.version)
        'Refresh mode'  = (Adb shell getprop persist.sys.mRefreshMode)
        'Size'          = (Adb shell wm size)
        'Density'       = (Adb shell wm density)
        'user_rotation' = (Adb shell settings get system user_rotation)
    }
    $props.GetEnumerator() | Sort-Object Name |
        ForEach-Object { '{0,-14} {1}' -f $_.Key, ($_.Value -join ' ') }

    Write-Host ''
    Write-Host 'Derived layout budget:' -ForegroundColor Cyan
    $scale = 212 / 160
    '{0,-14} {1}' -f 'scale', ('{0:N3}x (212dpi / 160)' -f $scale)
    '{0,-14} {1}' -f 'screen dp', ('{0:N0} x {1:N0} dp' -f (758 / $scale), (1024 / $scale))
    '{0,-14} {1}' -f '48dp target', ('{0:N0} px' -f (48 * $scale))
}

'look' {
    Assert-Device | Out-Null
    Assert-Awake
    $p = Join-Path $OutDir ("shot-{0}.png" -f (New-Stamp))
    Get-Screenshot -Path $p | Out-Null
    Write-Host $p
    if ($Open) { Start-Process $p }
}

'tree' {
    Assert-Device | Out-Null
    Assert-Awake
    $p = Join-Path $OutDir ("tree-{0}.xml" -f (New-Stamp))
    Get-UiTree -Path $p | Out-Null
    Write-Host $p
    if ($Raw) { Get-Content $p -Raw }
}

'probe' {
    # The workhorse: what is on screen + where to tap it.
    # Works on this device even when `look` returns a blank framebuffer.
    Assert-Device | Out-Null
    Assert-Awake
    $p = Join-Path $OutDir ("tree-{0}.xml" -f (New-Stamp))
    Get-UiTree -Path $p | Out-Null
    $rows = Parse-UiTree -Path $p
    $focusLine = @((Adb-Quiet shell dumpsys window) -split "`r?`n" |
        Select-String 'mCurrentFocus' | ForEach-Object { $_.ToString().Trim() })
    if ($focusLine.Count -gt 0) {
        Write-Host ("focus: {0}" -f $focusLine[0]) -ForegroundColor DarkGray
    }
    $rows | Where-Object { $_.Text -or $_.Desc -or $_.Clickable } |
        Select-Object Text, Desc, Id, Class, Clickable, Scrollable, TapX, TapY, W, H |
        Format-Table -AutoSize -Wrap
    Write-Host "tree: $p" -ForegroundColor DarkGray
}

'tap'    { Assert-Device | Out-Null; Adb shell input tap $X $Y;                              Write-Host "tapped $X,$Y" }
'swipe'  { Assert-Device | Out-Null; Adb shell input swipe $X $Y $X2 $Y2 $Duration;          Write-Host "swiped $X,$Y -> $X2,$Y2 (${Duration}ms)" }
'key'    { Assert-Device | Out-Null; Adb shell input keyevent $Value;                        Write-Host "key $Value" }
'back'   { Assert-Device | Out-Null; Adb shell input keyevent KEYCODE_BACK }
'home'   { Assert-Device | Out-Null; Adb shell input keyevent KEYCODE_HOME }

'text' {
    Assert-Device | Out-Null
    # `input text` needs spaces escaped and cannot carry most punctuation safely.
    $esc = $Value -replace ' ', '%s'
    Adb shell input text $esc
    Write-Host "typed: $Value"
}

'focus' {
    Assert-Device | Out-Null
    (Adb-Quiet shell dumpsys window) -split "`r?`n" |
        Select-String 'mCurrentFocus|mFocusedApp' | ForEach-Object { $_.ToString().Trim() }
}

'launch' {
    Assert-Device | Out-Null
    if (-not $Package) { throw "-Package required" }
    Adb-Quiet shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Milliseconds 1200
    (Adb-Quiet shell dumpsys window) -split "`r?`n" |
        Select-String 'mCurrentFocus' | ForEach-Object { $_.ToString().Trim() }
}

'apps' {
    Assert-Device | Out-Null
    (Adb-Quiet shell pm list packages -3) -split "`r?`n" |
        Where-Object { $_ -match 'package:' } |
        ForEach-Object { ($_ -replace 'package:', '').Trim() } | Sort-Object
}

'rotate' {
    Assert-Device | Out-Null
    # NOTE: this only takes effect for activities that permit rotation. Apps
    # locked to portrait (e.g. the Unlauncher home screen) will ignore it --
    # verified on this device. In-app rotation must use setRequestedOrientation().
    Adb shell settings put system accelerometer_rotation 0
    Adb shell settings put system user_rotation $Rotation
    Start-Sleep -Milliseconds 800
    Write-Host ("user_rotation = " + (Adb shell settings get system user_rotation))
}

'watch' {
    Assert-Device | Out-Null
    Assert-Awake
    $dir = Join-Path $OutDir ("watch-{0}" -f (New-Stamp))
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    for ($i = 1; $i -le $Count; $i++) {
        $png = Join-Path $dir ("{0:d2}.png" -f $i)
        $xml = Join-Path $dir ("{0:d2}.xml" -f $i)
        Get-Screenshot -Path $png | Out-Null
        Get-UiTree     -Path $xml | Out-Null
        Write-Host ("[{0}/{1}] {2}" -f $i, $Count, (Split-Path $png -Leaf))
        if ($i -lt $Count) { Start-Sleep -Milliseconds $IntervalMs }
    }
    Write-Host "captured to $dir"
}

'shot-series' {
    # Screenshot every third-party app's first screen -- a UI/UX survey pass.
    Assert-Device | Out-Null
    Assert-Awake
    $dir = Join-Path $OutDir ("survey-{0}" -f (New-Stamp))
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $pkgs = (Adb-Quiet shell pm list packages -3) -split "`r?`n" |
        Where-Object { $_ -match 'package:' } |
        ForEach-Object { ($_ -replace 'package:', '').Trim() }
    foreach ($pkg in $pkgs) {
        Adb-Quiet shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
        Start-Sleep -Milliseconds 2500
        $safe = $pkg -replace '[^\w\.\-]', '_'
        try {
            Get-Screenshot -Path (Join-Path $dir "$safe.png") | Out-Null
            Get-UiTree     -Path (Join-Path $dir "$safe.xml") | Out-Null
            Write-Host "captured $pkg"
        } catch { Write-Warning "$pkg -> $_" }
        Adb shell input keyevent KEYCODE_HOME | Out-Null
        Start-Sleep -Milliseconds 600
    }
    Write-Host "survey in $dir"
}

default {
    @'
einknav - UI/UX inspection harness for InkReader 6 (EPD106A)

  info                          device baseline + dp budget
  look   [-Open]                screenshot -> PNG (binary-safe pull)
  tree   [-Raw]                 uiautomator XML hierarchy
  probe                         on-screen text + tap coordinates  <-- start here
  focus                         focused activity
  apps                          third-party packages
  launch -Package <id>          start an app, report new focus
  tap    -X n -Y n
  swipe  -X n -Y n -X2 n -Y2 n [-Duration ms]
  text   -Value "hello"
  key    -Value KEYCODE_BACK
  back | home
  rotate -Rotation 0|1|2|3
  watch  [-Count n] [-IntervalMs n]
  shot-series                   screenshot every 3rd-party app

Output lands in .einknav/ next to the repo root.

Why `probe` and not just `look`: on this panel `screencap` can return an
all-black framebuffer while the screen is visibly on. `uiautomator dump`
still returns full text + bounds, so `probe` is the reliable channel and
`look` is the confirmation.
'@
}

}
