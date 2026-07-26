# InkDeck — Project Plan

A single Android app for the **InkReader 6** e-ink tablet: SSH terminal + remote file
explorer, task manager with Telegram notifications, a togglable market dashboard, and a
draggable floating action menu — all designed for a 16 fps grayscale panel.

- **Target device:** InkReader 6 / `EPD106A`
- **Plan status:** draft for review. Section 12 lists the open decisions.
- **Companion doc:** [design.md](design.md) — per-screen layouts, typography, contrast, components.
- **Navigation harness:** [tools/einknav.ps1](tools/einknav.ps1) — see §1.

---

## 0. Verified device baseline

Everything below was read off the device over adb on 2026-07-25, not assumed. These facts
drive most of the decisions in §2.

| Property | Value | Consequence for the app |
|---|---|---|
| Model / BT name | `EPD106A` / **InkReader 6** | — |
| Manufacturer / SoC | Allwinner `sun8iw15p1` ("virgo") | — |
| Android | **8.1.0, API 27** | `minSdk 26`, no modern platform APIs |
| ABI | **`armeabi-v7a` (32-bit only)** | every native lib must ship armv7; no arm64 |
| CPU | **2 cores** | no heavy render loops; keep work off the main thread |
| RAM | **938 MB total, ~550 MB available** | tight heap; avoid Compose/Flutter/WebView stacks |
| Panel | 758 × 1024 px @ 212 dpi | 572 × 773 dp logical |
| Panel refresh | **16.0 fps** (`supportedModes fps=16.0`) | **no animations, ever** |
| Status bar inset | **56 px top, no nav bar** | but see the window-geometry note below |
| App content area | **758 × 904 px = 572 × 682 dp** (`screenHeightDp=682`) | corrected in Phase 1 — 49 dp less than first assumed |
| Root | **not rooted** (`su: not found`) | no direct EPD/waveform control |
| OEM e-ink SDK | none in `/system/framework`, no e-ink permissions | but see the refresh **broadcast** below |
| E-ink refresh hook | **`android.eink.force.refresh`** broadcast, sent by SystemUI's `↻` button | likely usable refresh trigger — §3.4 |
| OEM floating ball | **`com.moan.floatball`** (悬浮球) already installed | reference implementation for §6 |
| Physical keys | `sunxi-keyboard`: **HOME** (102), MENU (139), ENTER, VOL±  | hardware HOME honours the default launcher |
| Google Play Services | **completely absent** (no `gms`, `gsf`, `vending`) | **no FCM push** → Telegram long-poll only (§7) |
| Installed IME | **only Simple Keyboard** (`rkr.simplekeyboard.inputmethod`) | Latin, but produces no Esc/Tab/Ctrl/Alt/arrows — key row still required (§6.1) |
| WebView | `com.android.webview` **61.0.3163.98** (Chromium 61, 2017) | rules out all web-based stacks |
| Frontlight | no readable `/sys/class/backlight` or `/sys/class/leds` | brightness control unavailable |
| `SYSTEM_ALERT_WINDOW` | supported | system-wide floating button possible |
| Android Keystore | **cannot generate app keys** — AES and RSA both throw `ProviderException` | vault falls back to a key file; see §4.3 |
| **`AlarmManager` refuses this app** | `linfeifei: dev.inkdeck, isFreeze not allown set Alarm` at every `set*`. The OEM launcher marks new packages frozen (`setApplicationFreeze … flag ->1`) and the patched framework drops their alarms. **Only `android` and the vendor OTA app hold alarms on this device** | **No exact scheduling and no wake-from-sleep for any sideloaded app.** Phase 4 delivers reminders with a polling ticker instead; Phase 5's poller cannot rely on alarms either. See §5.1b |
| OEM app killer | `linfeifei: BEGIN_MOGU_KILL_APP kill dev.inkdeck` — **force-stops the app 30 s after it goes to background**, measured across four reboots. Not prevented by a Doze whitelist entry, and not by a foreground service (killed at `adj 200`) | Anything that must keep running needs a foreground service *and* still cannot be guaranteed. See §5.1b-2 |
| Launcher | `com.moan.launcher` (OEM) | our app is a normal sideloaded app |
| Network | wlan0 `192.168.1.11/24` | same LAN as the dev machine |

**Window-geometry inconsistency, found in Phase 1 and worth knowing before laying anything
out.** The device reports two different sizes for the same window:

```
mFrame    = [0,0][758,1024]          content insets = top 56, bottom 0
appBounds = Rect(0, 0 - 758, 960)    screenHeightDp = 682   ( = 904 px below the status bar)
displayMetrics                        758 × 960 px
```

SystemUI sets `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` globally (`vsysui=0x2600`; the OEM
launcher does the same), so the window frame stretches over a **64 px navigation strip that
does not exist and reports no inset for it** — while `appBounds` still excludes it. The
consequence is that `android:id/content` is *measured* at 1024 − 56 = 968 px but *laid out* at
960 − 56 = 904 px. A vertical `LinearLayout` hands the entire 64 px surplus to its weighted
child and anything below that child falls off the bottom of the screen; in the first Phase 1
build it left a 10 px sliver of the 56 dp tab bar.

**Use `Configuration.screenHeightDp` (682 dp), never the display height.** `:core-eink`
provides `EinkGeometry.contentBox()` for this and `AppBoundsLinearLayout` as the safe root for
any full-screen layout with bottom chrome.

**Screenshot caveat that matters for design review:** `screencap` captures the **RGB
framebuffer, not the panel output**. Colour icons (F-Droid, SimplyTranslate) appear fully
coloured in a screenshot but dither to grey on the actual panel. Never validate contrast
from a screenshot alone — check it on the device.

---

## 1. Feature 1 — Screen navigation & UI/UX inspection harness

**Goal:** a repeatable loop to see what is on the device screen, know where to tap, and
drive the UI — so UI/UX can be reviewed without picking the tablet up.

Delivered as [tools/einknav.ps1](tools/einknav.ps1) (working, tested on this device).

### 1.1 The two channels, and why both are needed

| Channel | Command | Reliability |
|---|---|---|
| **Semantic** — `uiautomator dump` → XML with text + `bounds` | `einknav probe` | **Primary.** Returns full text and tap coordinates even when the framebuffer reads blank. |
| **Visual** — `screencap` → PNG | `einknav look` | Confirmation only. Returned an all-black PNG while the screen was on and awake. |

`probe` parses the XML into a flat table of *what is on screen and where to tap it*:

```
focus: mCurrentFocus=Window{2f476d0 u0 com.moan.launcher/com.moan.launcher.MainActivity}

Text            Id                      Class    Clickable TapX TapY  W  H
----            --                      -----    --------- ---- ----  -  -
Completed today                         TextView     False   379  178 184 33
File Manager    tv_app_name             TextView     False    94  891 138 46
F-Droid         tv_app_name             TextView     False   661  891  78 46
```

### 1.2 Command surface

```bash
powershell -ExecutionPolicy Bypass -File .\tools\einknav.ps1 info
```

| Command | Purpose |
|---|---|
| `info` | Device baseline + derived dp budget |
| `probe` | **Start here.** On-screen text + tap centres + focused activity |
| `look [-Open]` | Screenshot to PNG (binary-safe pull) |
| `tree [-Raw]` | Raw uiautomator XML |
| `tap -X n -Y n` | Tap |
| `swipe -X -Y -X2 -Y2 [-Duration]` | Swipe / drag (used to test the floating button) |
| `text -Value "…"` | Type text |
| `key -Value KEYCODE_…` | Key event |
| `back` / `home` | Navigation |
| `focus` | Focused activity |
| `launch -Package <id>` | Start an app, report resulting focus |
| `apps` | Third-party packages |
| `rotate -Rotation 0..3` | Display rotation |
| `watch [-Count] [-IntervalMs]` | Timed screenshot + tree pairs (catches refresh artefacts) |
| `shot-series` | Screenshot every third-party app — UI/UX survey pass |

Output goes to `.einknav/`. Device-side temp files are removed after every pull.

### 1.3 Gotchas already solved in the script

- **Never** pipe `adb exec-out screencap` through PowerShell `>` — it injects a BOM and
  re-encodes, corrupting the PNG. Write device-side, then `adb pull`.
- Console output is forced to UTF-8; the stock UI is largely Simplified Chinese and
  renders as mojibake otherwise.
- `adb shell settings put system user_rotation` **does not rotate a portrait-locked
  activity** (verified: value changed to 1, display stayed at rotation 0). In-app rotation
  must use `setRequestedOrientation()` — this is why §6.4 is an app-owned feature.
- In PS 5.1, `-split` returning a single line yields a *scalar string*; indexing `[0]`
  gives the first character. Wrap in `@()`.

### 1.4 How this is used during development

1. `einknav shot-series` once, to bank the OEM design language (spacing, stroke weight, type sizes).
2. After each build: `adb install -r`, then `probe` to assert the expected tree, `look` to eyeball it.
3. `watch -Count 8 -IntervalMs 1200` across a screen transition to see ghosting and how many
   partial refreshes occur before a flush is needed.
4. Automate regression checks by asserting on `probe` output (text present, tap targets ≥ 64 px).

---

## 2. Technology decision

### 2.1 Choice: **native Kotlin + classic Android Views** (XML layouts, no Compose)

### 2.2 What was ruled out, and why

| Option | Verdict | Reason (device-specific) |
|---|---|---|
| Capacitor / Ionic / React Native (web) | **Rejected** | WebView is **Chromium 61** and is `com.android.webview` — AOSP, **not updatable** without Play. No modern JS/CSS baseline. |
| Flutter | **Rejected** | Skia compositor targets 60 fps against a 16 fps panel; 32-bit armv7 support is winding down; heavy for 2 cores / 550 MB free. |
| Jetpack Compose | **Rejected** | Runs on API 26, but is animation-first (ripples, transitions, smooth scroll) — precisely what a 16 fps e-ink panel cannot render. Higher startup and RAM cost. Fighting its defaults costs more than writing Views. |
| Native Views | **Chosen** | Direct `Canvas` control, deterministic invalidation, cheap, and every animation is opt-in rather than opt-out. |

### 2.3 Build configuration

```
minSdk        26
targetSdk     28      # sideloaded — no Play targetSdk mandate; keeps API 27 behaviour predictable
compileSdk    35
ndkAbiFilters armeabi-v7a
Java          17 toolchain + core library desugaring (SSH libs need java.time/nio)
```

- **No Play Services dependency anywhere** — it is not on the device.
- Keep the APK lean; ship only `armeabi-v7a` and only the font weights actually used.

### 2.4 Networking on API 27 — a real trap

Android 8.1's CA store and TLS stack are dated. Mitigations:

- OkHttp with an explicit **TLS 1.2** `ConnectionSpec` (TLS 1.3 is unavailable).
- Bundle **Conscrypt** (`org.conscrypt:conscrypt-android`) and install it as the security
  provider at startup — gives a modern TLS + cert stack independent of the OS.
- Ship a current trust store for the market/LLM endpoints rather than trusting the 2017 CA set.

---

## 3. Architecture

### 3.1 Modules

```
:app                    shell, navigation, floating menu host, theme switching
:core-eink              refresh policy, EinkTheme tokens, base widgets, paged scrolling
:core-data              Room, DataStore, secret vault (Keystore + passphrase KDF)
:core-net               OkHttp + Conscrypt, TLS config, retry/backoff
:feature-terminal       SSH session, PTY, terminal view, key row
:feature-files          SFTP browser (sidebar + full screen)
:feature-tasks          task CRUD, scheduler, notification dispatch
:feature-market         provider adapters, widget grid, Canvas charts
:feature-ai             BYOK chat (OpenAI-compatible + Anthropic)
:integration-telegram   long-poll foreground service, command router
```

### 3.2 Patterns

- MVVM: `ViewModel` + `StateFlow`, Views observe and re-render **once per state change**
  (never per frame).
- Single `Activity`, `Fragment` per feature, no shared-element transitions.
- Room as the single source of truth; Flow-driven UI.
- All I/O on `Dispatchers.IO`; the 2-core CPU means blocking the main thread is instantly visible.

### 3.3 Threading and battery

E-ink readers sleep aggressively. Two long-lived needs (SSH session, Telegram poll) must
survive that:

- One **foreground service** with a persistent (low-priority) notification hosting both.
- `PARTIAL_WAKE_LOCK` held **only** while a command is in flight or a poll is open.
- ~~Adaptive Telegram polling (§7.3)~~ — struck, see §7.3: the device grants no timer to wake on.
- Request battery-optimisation exemption once, explaining why.

### 3.4 E-ink refresh strategy — the core of feeling right

**Correction to an earlier assumption.** There *is* an OEM refresh hook. SystemUI's status-bar
`↻` button works by sending a plain broadcast:

```
android.eink.force.refresh
```

It is logged as a **non-protected** broadcast (`checkBroadcastFromSystem` StrictMode warning),
which means it has no signature permission guarding it — so an ordinary app can very likely
send it too. Sending it from `adb shell am broadcast` is accepted (`Broadcast completed`).

✅ **VERIFIED WORKING — Phase 1, on the panel, 2026-07-25.** Sending this broadcast from
ordinary app code visibly flushes the display and clears accumulated ghosting. A/B'd in
`EinkLabActivity` against the invert-and-restore fallback and a no-flush control; the broadcast
alone was enough.

**So the flush is a one-liner**, not a hack: `sendBroadcast(Intent("android.eink.force.refresh"))`.
No blanked screen, no swallowed taps, nothing to clean up. This matters most in the terminal,
where an invert-and-restore flush would black out output mid-read every time the ghost budget
tripped. The invert-and-restore path is implemented and kept as a per-surface fallback.

The panel still performs a visible full-screen waveform, so a flush is cheap for the *app* but
not free for the *reader* — the ghost budget in §3.4 point 2 stays.

**Static analysis said the opposite, and was wrong.** `dumpsys activity broadcasts` was searched
in full:
the action appears in **neither** the manifest Receiver Resolver Table **nor** as a dynamic
`BroadcastFilter`, and every send completes in a few ms with zero recipients — textbook signs of
an inert intent. It flushes anyway. Whatever handles it sits below the app framework, most
likely the Allwinner `aw_display` / `com.softwinner.IDisplayService` path (there is also a
`/sys/class/disp` node; neither is reachable unprivileged). **Takeaway for this device: an empty
resolver table is not evidence of absence — test on the panel.**

**And `einknav watch` cannot settle questions like this.** `screencap` reads the RGB
framebuffer, which is byte-identical before and after a waveform refresh — a panel flush is
invisible to it. Only a person looking at the panel can judge. `:app` ships a debug
`EinkLabActivity` for exactly that: it accumulates ghosting on purpose, then runs each strategy
in isolation against a witness field, with a no-flush control so a strategy that does nothing
cannot be mistaken for one that works.

```
adb shell am start -n dev.inkdeck/dev.inkdeck.lab.EinkLabActivity
```

Re-run it after any ROM change; this hook is undocumented and unprotected, so it could vanish.

Two related OEM broadcasts also exist and are unguarded: `com.mogu.show_shutdown_key`,
`MY_SHUTDOWN_MSG`.

Beyond that hook, no waveform API is reachable (not rooted, no OEM e-ink jar). So:

**Policy, implemented in `:core-eink`:**

1. **Default: partial refresh.** Invalidate the smallest possible rect. Typing a terminal
   character must not repaint the screen.
2. **Ghost budget.** Count partial refreshes per surface; after **N = 8** (tunable), or on
   any full screen/tab change, perform a **flush**.
3. **Flush.** Broadcast `android.eink.force.refresh` — verified above. Implemented as
   `BroadcastFlush`; `InvertRestoreFlush` (full-bleed black for two frames, white for one,
   then restore) remains available per-surface as a fallback.
4. Expose flush manually on the floating menu (§6.5) — the OEM status bar has a `↻` button,
   proving users expect a manual refresh affordance.
5. Globally disable: ripples, activity transitions, overscroll glow, fling scrolling,
   `notifyDataSetChanged` animations, indeterminate progress spinners.
6. Replace fling scrolling with **paged scrolling** (§design.md) — one full-page jump per
   tap is far better than smooth scroll at 16 fps.

**Verify with:** `einknav watch -Count 8 -IntervalMs 1200` across transitions.

---

## 4. Feature 2a — SSH terminal + file explorer sidebar

Connect to any host from user-supplied config; `t4-aws-binh` is the reference host, with its
`.pem` stored **on the InkReader**.

### 4.1 Library choices

| Need | Choice | Note |
|---|---|---|
| SSH transport, exec, SFTP | **`com.github.mwiede:jsch`** | Maintained JSch fork; modern algorithms (`rsa-sha2-*`, ed25519), works on old Android, no BouncyCastle needed for PEM. |
| Alternative | `com.hierynomus:sshj` | Cleaner API but pulls BouncyCastle and needs more desugaring. Fallback if jsch blocks. |
| Terminal emulation | **vendored ConnectBot `de.mud.terminal.vt320`** (⚠️ **GPL-2.0-or-later**, not BSD) | Battle-tested on old Android; pure-Java VT100/xterm state machine. See the licence note below. |
| Terminal rendering | **custom `TerminalView` on `Canvas`** | Draws only dirty cell rects → partial refresh; a `TextView` cannot do this. |

⚠️ **Licence correction (Phase 2).** This table originally recorded vt320 as BSD. It is not.
The headers on the vendored files read:

| File | Licence |
|---|---|
| `vt320.java`, `VDUBuffer.java`, `VDUDisplay.java`, `VDUInput.java` | **GPL-2.0-or-later** ("JTA — Telnet/SSH for the JAVA platform", © Matthias L. Jugel, Marcus Meiner 1996–2005) |
| `Precomposer.java` | Apache-2.0 |

Linking GPLv2 code makes InkDeck a derivative work, so **InkDeck as a whole is GPL-2.0-or-later
the moment it is distributed**. For a sideloaded personal build on one device that obligation
never triggers — GPL duties attach on distribution, not use — so this is not a blocker for the
stated goal. It *would* matter for a Play release, which §11 already lists as a non-goal.

The vendored sources and their headers are under
`feature-terminal/src/main/java/de/mud/terminal/`, with provenance in `NOTICE.md` there.

If a permissive licence is ever wanted, the realistic swap is **Termux's
`terminal-emulator`** (Apache-2.0) — a modern, actively maintained Android VT/xterm emulator.
It is a larger change than a drop-in: it owns its own `TerminalSession` and PTY handling rather
than exposing a bare `VDUBuffer` for a custom `Canvas` renderer to draw.

### 4.2 Host configuration

Parse an OpenSSH-config subset so the user can paste what they already have:

```
Host t4-aws-binh
    HostName ec2-54-169-158-168.ap-southeast-1.compute.amazonaws.com
    User binh
    IdentityFile "D:\VAYLA\vayla_trading_bot_key_pair.pem"
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
```

Supported keys: `Host`, `HostName`, `User`, `Port`, `IdentityFile`, `StrictHostKeyChecking`,
`UserKnownHostsFile`, `ServerAliveInterval`, `Compression`.

- Windows paths in `IdentityFile` are **rewritten** on import: the key is copied into the
  app's private vault and the entry re-pointed. A `D:\…` path is meaningless on Android.
- Import routes: paste into the app · `adb push` + in-app file picker · Telegram (§7.4).

**No host editor exists yet, and this is the stand-in.** A host is otherwise only creatable by
importing a whole `ssh_config`, which is awkward for one entry. `AdbImport` now also accepts a
one-line host definition from `.env`:

```
SSH_HOST_<alias>=user@host:port key=<vault-id> strict=<yes|ask|no>
```

Only `user@host` is required. `strict` defaults to `ASK` — trust-on-first-use — exactly as the
`ssh_config` parser does, and setting `no` emits a warning naming the host. `SSH_HOST_*` is the
one `.env` key that does **not** go into the vault: it is configuration, not a secret, and it
belongs in `HostStore` where the host list can read it.

Typing a host into a form on a 16 fps panel is worse than typing it into a file on the desktop,
so this may well outlive the editor it stands in for.

**One security note, then it is your call.** `StrictHostKeyChecking no` +
`UserKnownHostsFile /dev/null` disables host-key verification, which is what makes a
man-in-the-middle on a coffee-shop network possible — and this key reaches a trading server.
Recommendation: the app defaults to **trust-on-first-use** (pin the host key on first
connect, warn loudly if it later changes), which preserves the "no prompts" convenience your
config is after while still detecting an actual MITM. The permissive setting remains
available per-host if you want it.

### 4.3 Key storage (`.pem` on device)

- Keys live in app-internal storage (`filesDir/vault/`), never on `/sdcard`.
- Encrypted **AES-256-GCM** under a random 256-bit data key. Secrets are `iv ‖ AES-GCM(dk, …)`.
- Vault unlocks once per app session, held in memory only, zeroed on lock.
- Decrypted key material is **never** written to disk and never logged.

**Revised in Phase 2 — one wrap, not two, and no passphrase by default.**

The original design wrapped the data key twice, by Keystore *and* by a passphrase, so either
could open it. That is not redundancy, it is a downgrade: a vault the device can open unaided is
only ever as strong as the device, and the passphrase becomes decoration. There is now exactly
one active wrap, and switching modes rewraps and deletes the other:

| Mode | Wrap | When |
|---|---|---|
| **`DEVICE`** | `DeviceKey.encrypt(dk)` | **default** — single-user device, no prompt |
| `PASSPHRASE` | `AES-GCM(PBKDF2-HMAC-SHA256(pass, salt, 120k), dk)` | opt-in toggle |

⚠️ **This device's keymaster cannot hold a key at all.** Both `KeyGenParameterSpec` paths throw
`ProviderException`:

```
W InkDeckVault: device key 'keystore-aes' unusable: ProviderException
W InkDeckVault: device key 'keystore-rsa' unusable: ProviderException
I InkDeckVault: device key strategy = local-file
```

So `DeviceKey` tries AES → RSA → **a random key file in `filesDir/vault/device.key`**, and each
candidate must pass an encrypt/decrypt round trip before the vault commits to it. (Generating a
key can succeed while *using* it fails — skipping that check is what made vault creation fail
silently the first time.)

Be clear about what `local-file` buys: on a non-rooted device the per-app UID keeps other apps
out of `filesDir`, and the vault survives an offline read of the flash. It does **not** resist
root, or `adb run-as` against a debuggable build. That is an acceptable trade for a sideloaded
single-user reader, and it is why the passphrase toggle exists — the UI states which backing is
in use rather than implying hardware protection that is not there.

⚠️ **`SecretVault` must be a single process-wide instance — found late, during Phase 5/7/8
integration.** Four modules (`:feature-terminal`, `:feature-telegram`, `:feature-market`,
`:feature-ai`) had each written `SecretVault(context)` independently. The unlocked data key is
per-instance state, so with `Protection.DEVICE` (the default) every instance unlocks itself and
the bug is invisible — it would only have surfaced the first time someone turned the passphrase
on, at which point unlocking in one tab would leave every other tab still asking for it. Fixed
with `SecretVault.get(context)`, a `@Volatile`-guarded singleton in `:core-data`; every call site
now goes through it. **Never call the `SecretVault` constructor directly outside `:core-data`.**

**Secrets can be bulk-loaded from a `.env`** pushed to the import directory (§7.4 route): each
`KEY=value` becomes one vault entry and the file is shredded on ingest. `VAULT_PASSPHRASE` is
explicitly refused — a passphrase in plaintext beside the vault it opens protects nothing.

### 4.4 Terminal behaviour

- PTY size negotiated from the actual view: at 13 sp mono in landscape ≈ **70 × 26 cells**;
  portrait with the sidebar collapsed ≈ 56 × 40. Send `SIGWINCH` on rotation.
- `ServerAliveInterval 30` to survive the reader's sleep cycles; auto-reconnect with backoff
  and a visible banner.
- Scrollback 5 000 lines, capped by memory pressure.
- Custom key row (§6.1) supplies Esc / Tab / Ctrl / Alt / arrows / pipe / tilde — no IME can
  produce these. Ctrl and Alt **latch for one keystroke** and compose with letters typed on the
  system IME, so `Ctrl` on the row then `c` on Simple Keyboard sends `^C`.

**Two IME traps, both hit and fixed in Phase 2 — worth knowing before touching input:**

1. **`InputType.TYPE_NULL` silently loses everything you type.** It asks the IME for raw key
   events, but an IME that honours it routes committed text through `BaseInputConnection`'s
   dummy mode, which arrives as a `KeyEvent.ACTION_MULTIPLE` and lands in `onKeyMultiple` — not
   `onKeyDown`. `TerminalView` now declares
   `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`
   (the standard terminal trick to suppress autocorrect and the composing region) and supplies
   its own `InputConnection` that forwards `commitText` straight to the channel.
   `onKeyMultiple` is handled as well, for IMEs that take the other route.

   ⚠️ **`adb shell input text` cannot catch this class of bug.** It injects `ACTION_DOWN` events
   directly into the View and bypasses the IME entirely, so terminal input tested perfectly
   while being completely broken for a human. Test typing by tapping the on-screen keyboard.

2. **`setPtySize` from the UI thread kills the session.** Writing a `window-change` request onto
   the session while JSch's reader thread is live on it makes the server close the channel about
   a second later — with no exception on our side, so it looks like a random disconnect. It
   reproduces every time the soft keyboard opens, because that resizes the window. `SshSession`
   now serialises every out-of-band request onto one control thread and skips no-op resizes.
- Output rendering is throttled to **≤ 8 repaints/second** regardless of data rate; a
  `yes`-flood must not melt the panel.
- **`TERM=vt220`, not `xterm`.** The vendored vt320 is a VT220-class emulator. Claiming xterm
  made `vim` send sequences it cannot parse: `CSI > 4 ; m` (modifyOtherKeys) spilled onto the
  prompt as literal `4;m`, and the alternate-screen switch was ignored. Advertising what the
  emulator actually is fixes the corruption without patching the GPL sources. Cost is
  256-colour, which the grayscale panel discards anyway.
  - ⚠️ **Known limitation:** with no alternate screen buffer, exiting a full-screen app leaves
    its last frame on screen — authentic VT220 behaviour, and `clear` resets it. Supporting it
    properly means intercepting `CSI ? 1049 h/l` in `TerminalEmulator` and clearing on exit,
    which keeps the vendored code untouched. Not done yet.

### 4.5 File explorer

- SFTP over the same session (no second connection).
- Sidebar drawer in portrait, persistent split in landscape (§design.md).
- Operations: browse, rename, delete, mkdir, upload, download, chmod, "cd here in terminal",
  view text files.
- No thumbnails; type icons only.
- Long paths middle-ellipsised.

---

## 5. Feature 2b/2c — Tasks and Market

### 5.1 Task manager with time settings + Telegram notification (CRUD)

**Data model**

```
Task(id, title, notes, dueAt, remindAt[], repeatRule, priority,
     tags[], status, telegramNotify, createdAt, updatedAt)
```

**Scheduling** — API 27 is *easier* here than modern Android:

- `AlarmManager.setExactAndAllowWhileIdle()` needs **no** `SCHEDULE_EXACT_ALARM` permission
  (that arrived in API 31). Use it for reminders.
- `WorkManager` for retryable delivery (send-to-Telegram with backoff when offline).
- `BOOT_COMPLETED` receiver re-arms alarms after reboot.

**Notification paths** — ⚠️ **reversed after Phase 4; see §5.1d.**

1. ~~Local Android notification (always).~~ Demoted to a fallback.
2. Telegram `sendMessage` to the paired chat — now the primary route.

**Repeat rules:** none · daily · weekdays · weekly(days) · monthly(day) · every N days.
Store the rule, compute the next occurrence on completion — do not pre-generate instances.

### 5.1a As built (Phase 4)

Three departures from the sketch above, all deliberate:

1. **`remindAt[]` is stored as offsets in minutes before `dueAt`, not as absolute instants.**
   design.md §8.2 presents REMIND as a lead time (`none · 10m · 1h · 1d`), and an offset is the
   only form that survives a repeat — roll a weekly task forward and an absolute reminder is
   stranded in the past, while "1 h before" still means the same thing. The editor writes one
   offset; the column and the scheduler both take a list, up to `MAX_REMINDERS = 4`.
2. **design.md's `cust` chip is `on time` (offset 0).** A due time with no reminder at that
   time is the surprising case, not the useful one; a free-form offset would need its own
   picker for something the four fixed steps already cover. Picking a date on a task that had
   none also selects `on time` automatically, and never overwrites a choice already made.
3. **Completing a repeating task rolls `dueAt` forward and leaves it OPEN** rather than writing
   a DONE row — that is what "compute the next occurrence on completion" means in practice.
   `completedAt` still records the tick, and the UI says where the task went.

**Repeat arithmetic runs in `LocalDateTime` at the task's zone, never by adding milliseconds.**
"Daily at 08:00" must stay 08:00 across a DST boundary. Monthly clamps rather than rolls over:
the 31st in February is the 28th, not 2 March, which would drift the task a month at a time.

### 5.1c Per-task time zone

Requested during Phase 4, and the right call for this device: the trading server is in
`ap-southeast-1` and Binance publishes in UTC, so "14:00" is a genuinely ambiguous thing to
write on a market reminder.

`Task.zoneId` stores the zone the due time was **entered** in — empty means "follow the device".
`dueAt` is still a zone-independent epoch instant, so **this changes nothing about when an alarm
fires.** It changes what the user typed and what they read back.

- **Editor**: a `Device +07 | UTC` segmented control under DUE. The date and time pickers, and
  their "Today"/"Tomorrow" labels, are all expressed in the selected zone — offering the local
  date while the user is thinking in UTC would put the task a day out at either end of the day.
- **Switching zone keeps the instant and re-reads the clock face**, not the other way round. A
  user who set 21:00 local and taps UTC is asking "what is that in UTC?"; answering 14:00 is
  informative, while silently re-pointing the alarm to 21:00 UTC would move it seven hours
  without anything on screen changing to say so.
- **The list appends the zone** (`14:00 UTC`) only when it differs from the device's, so `14:00`
  can never be misread as local — and the suffix stays rare enough to still register as a
  warning rather than noise.
- **Sections stay in the device zone.** `TODAY` answers "what is on my plate today", and the
  reader's today is the local one.
- **`zoneId` is stored empty, not resolved, for the device case** — a task written "in local
  time" should follow the device if it ever moves, rather than pinning itself to whatever zone
  the tablet was in when it was typed.

Schema **v1 → v2**, additive with an `''` default, migration checked in. No destructive
fallback.

**Alarms are lost two ways, and both are covered.** Reboot is the documented one, handled by
`BootReceiver` (`BOOT_COMPLETED`, plus `MY_PACKAGE_REPLACED` because installing over the app
clears its alarms too — during development that happens far more often than a reboot). The
second is the ROM's `MOGU_KILL_APP` sweep from §0: Android cancels every alarm of a
force-stopped package and sends no broadcast saying so, so `MainActivity.onCreate` re-arms
unconditionally as well. It is one indexed query.

**No `SCHEDULE_EXACT_ALARM` in the manifest** — that permission arrived in API 31 and this app
targets 28, so `setExactAndAllowWhileIdle` needs nothing declared. Raising `targetSdk` past 30
would make exact reminders a permission-gated feature overnight; `TaskScheduler` logs the
`SecurityException` rather than silently losing the alarm if that ever happens.

**Room 2.6.1 + KSP `2.0.21-1.0.28`**, schema JSON checked in under `core-data/schemas/`. No
`fallbackToDestructiveMigration`: tasks are user data with reminders attached, and silently
dropping the table on a schema mismatch is worse than a crash that says so. The database holds
no secrets — it is plaintext SQLite, and keys stay in the §4.3 vault.

**Telegram delivery is stored but not sent — true as of Phase 4, superseded by §5.1d/§5.1e
below.** `telegramNotify` persists and the editor shows the toggle; `ReminderReceiver` logged the
intent and left delivery to Phase 5, which has since built the bot, the pairing, and the queue.

### 5.1d Telegram is the primary reminder channel; local notification is the fallback

§5.1 assumed the phone model: fire a local notification, optionally mirror it to Telegram. On
this device that is backwards, and the owner said so plainly after watching it fail — *"thiết bị
này không phải hoàn toàn là 1 thiết bị di động"*. The measurements in §5.1b and §5.1b-2 agree:

- `AlarmManager` is refused, so **nothing can wake the device** for a local notification.
- The ROM force-stops the app 30 s after it backgrounds, so even a delivered notification only
  lands if the reader happens to be awake and in your hand.
- A reader spends most of its life asleep in a bag. A phone does not.

The Telegram bot has none of those problems: the message is delivered by Telegram's
infrastructure to a device the owner actually carries, and it survives the tablet being off.

**Implementation.** `ReminderDelivery` in `:feature-tasks` is a list of routes tried in order,
falling back to the local notification when nothing better is registered. `:feature-telegram`
registers its route once the bot is paired. The dependency points that way —
`:feature-telegram` → `:feature-tasks` — so the task module never learns Telegram exists.

**`ReminderTicker` still owns the timing**, because something must notice the minute passed and
`AlarmManager` will not. Once the Telegram service is running it is already a foreground service
holding the process up, so the ticker belongs there and `ReminderGuardService` — with its
permanent notification — is only needed when the bot is not configured.

**Registered on boot, not only on first app open.** `:feature-telegram` ships its own
`TelegramBootReceiver` (`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, mirroring `:feature-tasks`'s
`BootReceiver`) calling `TelegramGraph.startIfEnabled`. Without it, a device that reboots and is
never physically opened — which is the whole point of Telegram being the reminder channel in the
first place — would run `ReminderTicker` in a process where the Telegram route was never
registered, and every reminder would silently fall back to the local notification it exists to
replace.

### 5.1e The outbound queue — closing the "paired but offline" gap

Even with the route registered, one case was still a real loss: **paired, token present, device
offline at the moment a reminder fires.** `canNotify()` (below) has to stay optimistic — it
cannot block on the network from a `BroadcastReceiver` — so it would say yes, `dispatch()` would
stop at that "success" and skip the local fallback, and the actual send would then fail silently.

Fixed with a small persisted queue (`OutboundQueue`, `:feature-telegram`) rather than
`WorkManager`: `notifyTask` now *enqueues* instead of sending inline, and returns "accepted"
because enqueuing is a more honest acceptance than a bare synchronous attempt ever was. The queue
drains at the top of every poll cycle, and once immediately on service start — the only window in
which a send can ever succeed is the window in which the poll loop is already running, so a queue
that only drains inside that loop needs no scheduler of its own, which is exactly the property
`WorkManager`/`JobScheduler` cannot offer on this device (§5.1b, §5.1b-2).

- **TTL 12 h**, shorter than a mail queue would use, and deliberately so — the same reasoning as
  `ReminderTicker.GRACE_MS` at a different scale. A reminder delivered two days late reads as a
  live alert for something long past; the task is already sitting under `OVERDUE`, readable
  calmly, whenever the device comes back.
- Capped at 32 entries, oldest dropped first — ~550 MB free and nobody reads the 33rd backlogged
  reminder.
- Holds rendered text and a dedupe key only — **never a token or chat id**. Both are resolved
  from the vault at send time, so a queue entry that outlives a re-pair cannot deliver to the old
  chat, and nothing credential-shaped sits in `SharedPreferences`.
- Dedupe key is `taskId:dueAt:slot`, the same shape `ReminderTicker` fires on, so a repeated tick
  cannot enqueue the same reminder twice.

Verified live: a reminder queued, drained, and delivered on the next poll cycle with the device
online throughout (the offline case is logically covered by the same drain path and was not
separately forced during this test pass).

### 5.1b Device fact: `AlarmManager` does not work for this app at all

This is the largest device constraint found so far, and it invalidates the scheduling approach
in §5.1 as written. **Every `AlarmManager.set*` call from this package is silently discarded by
a vendor patch.** The call returns normally, throws nothing, and the alarm never appears in
`dumpsys alarm`. Not deferred, not batched — absent.

The framework says so directly, at the moment of the call:

```
D linfeifei: setApplicationFreeze package_name ->dev.inkdeck, flag ->1   ← from com.moan.launcher
D linfeifei: dev.inkdeck,  isFreeze not allown set Alarm                 ← at every set*
```

`com.moan.launcher`, the OEM launcher, marks each newly installed package **frozen**, and the
patched `AlarmManager` refuses alarms from frozen packages.

**Everything ruled out**

| Hypothesis | Test | Result |
|---|---|---|
| Doze / battery optimisation | `dumpsys deviceidle whitelist +dev.inkdeck`, reboot | Still refused |
| Background-execution limits | App in foreground, screen on, alarm set from a tap | Still refused |
| Wrong alarm API | `setAlarmClock` instead of `setExactAndAllowWhileIdle` | Still refused; `Next alarm clock information:` stays empty |
| Freeze clears on use | Launched from the OEM launcher rather than `adb am start` | Still refused |
| Specific to our package | Scan of the entire alarm table | Only `android` and `com.abupdate.fota_demo_iot`, the vendor OTA app, hold alarms. **No third-party package on the device holds a single alarm** |

**Verified working.** With the ticker in place, a reminder set for 02:20 delivered end to end:

```
02:20:11.144  I InkDeckAlarm: tick fires task=1 slot=0 late=11122ms
02:20:11.162  I InkDeckAlarm: receive dev.inkdeck.tasks.REMIND task=1
02:20:11.231  I InkDeckAlarm: posted reminder for task=1 "Rotate Binance API key"
02:20:11.278  I InkDeckAlarm: re-armed 0 alarm(s) across 1 task(s)
02:20:11.285  I InkDeckAlarm: ticker stop
```

11 s late — inside the 30 s tick — with the notification posted on `task_reminders` and the
guard service standing itself down once nothing was left pending. The device was awake; the
deep-sleep case is the limitation below, not something this fixes.

**What Phase 4 does instead.** `ReminderTicker` runs a 30 s poll inside `ReminderGuardService`,
compares the wall clock against pending reminder instants, and broadcasts to the same
`ReminderReceiver` that `AlarmManager` would have invoked — so notification, repeat and Telegram
behaviour stay in one place. `TaskScheduler` is still called on every write: it decides which
reminders are pending, and if the package is ever taken off the freeze list, exact delivery
starts working with no code change.

**The honest limitation: nothing can wake the device from deep sleep.** That is the one thing
only `AlarmManager` can do. A reminder that comes due while the CPU is suspended fires as soon
as the device next runs, and sits under `OVERDUE` until then. For a reader that is picked up
several times a day this is a real but survivable gap; for a hard deadline it is not good
enough, and the only fix is getting the package off the vendor freeze list.

**Ask the user to look for a "protected apps" / autostart / freeze list in the device's own
Settings.** Nothing is reachable over adb — no `settings` key matching `white`, `mogu`, `freeze`
or `protect`, and no autostart-manager package installed — but this ROM clearly maintains such a
list, and putting InkDeck on it would restore exact, wake-from-sleep reminders.

### 5.1b-2 Second, separate problem: the force-stop sweep

This was found first, while chasing the reboot criterion, and looked like the whole story until
the alarm table was actually read. It is a genuine second constraint, and it is why
`ReminderGuardService` exists at all — the ticker in §5.1b can only run while the process does.

```
01:27:12  I InkDeckAlarm:   re-arming after android.intent.action.BOOT_COMPLETED
01:27:42  D linfeifei:      BEGIN_MOGU_KILL_APP kill dev.inkdeck
01:27:42  I ActivityManager: Force stopping dev.inkdeck appid=10054 user=0: from pid 1907
```

Thirty seconds after boot the ROM force-stops the package; `dumpsys package dev.inkdeck` then
shows `stopped=true`. On stock Android a force-stop also cancels every alarm the package owns
and stops delivering broadcasts to it — here the alarms were never registered anyway (§5.1b),
so what the sweep actually costs us is the **process**, and with it the ticker.

**The sweep's timing rule.** In every observation the kill lands **exactly 30.0 s** after the
ROM logs `computeOomAdjLocked begin add ->dev.inkdeck`, i.e. 30 s after the process is
re-evaluated for background:

| `computeOomAdjLocked add` | kill | Δ |
|---|---|---|
| 01:07:48.5 | 01:08:18.6 | 30.1 s |
| 01:27:12.0 | 01:27:42.0 | 30.0 s |
| 01:45:25.1 | 01:45:55.1 | 30.0 s |

**What was tried and does not work**

| Attempt | Result |
|---|---|
| Doze whitelist (`dumpsys deviceidle whitelist +dev.inkdeck`, i.e. what `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` grants) | Killed anyway. The OEM sweep does not consult AOSP's battery-optimisation list |
| Foreground service (`ReminderGuardService`) | **Killed at `adj 200` with `MainActivity` still alive** — `Killing 2717:dev.inkdeck/u0a54 (adj 200): stop dev.inkdeck`, followed by `Force finishing activity … MainActivity`. A foreground service raises the adj and does not stop this sweep |
| Searching for an OEM whitelist over adb | Nothing exposed: no `settings` key matching `white`/`mogu`/`freeze`/`protect`, and no autostart-manager package installed |

**No app-side mechanism can prevent an OEM force-stop.** Alarms, `JobScheduler` jobs and sticky
services are all cancelled by it, by design — and here even `adj 200` is not spared. The ROM
also schedules `Scheduling restart of crashed service … in 1000ms` after killing the service,
which never happens, because a force-stopped package is not restarted.

**What actually holds**

1. **Re-arm on every app start**, not just on boot — `MainActivity.onCreate` calls
   `TaskGraph.rearmAsync` unconditionally. Opening the app once after a reboot restores every
   pending reminder. This is implemented and verified.
2. **Overdue work stays visible.** A reminder missed this way still shows in the list under
   `OVERDUE` with the 4 dp bar, so a swept alarm degrades to "you see it next time you look"
   rather than vanishing.
3. **A device-settings whitelist, if this ROM has one in its own UI.** Nothing is reachable over
   adb, but OEM ROMs usually keep a "protected apps" or "autostart" list in Settings that is not
   exposed as a `settings` key. Worth checking on the device; it is a user action, not something
   the app can grant itself.

This also **raises the risk on Phase 5**: the Telegram long-poll service faces the same sweep,
and §0's original mitigation ("foreground service + battery exemption") is now known to be
insufficient on its own.

### 5.2 Market dashboard

A grid of small, individually **togglable** widgets; the user picks which markets to show.

**Provider abstraction** — this is the important part, because the free sources are unequal:

```kotlin
interface MarketProvider {
    val id: String
    suspend fun quote(symbol: String): Quote          // last, change, changePct, volume
    suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle>
    val attribution: String
}
```

| Market | Provider | Key? | Standing |
|---|---|---|---|
| **Crypto** | Binance public REST (`/api/v3/ticker/24hr`, `/api/v3/klines`) | No | **Documented & stable.** Best of the three. |
| Crypto (fallback) | CoinGecko free | Demo key | Rate-limited |
| **US stocks** | Finnhub free tier | Free key | Documented, ~60 req/min |
| US (no key) | Stooq CSV | No | Delayed, no docs, simple |
| **VN stocks** | VNDirect `dchart`, TCBS `apipubaws`, SSI iBoard | No | ⚠️ **Unofficial and undocumented.** Undeclared endpoints that can change or start requiring headers/referer without notice. |
| US stocks (no key), **as of live testing 2026-07-26** | Stooq | No | ⚠️ **Broken.** `stooq.com/q/d/l/` now answers a JavaScript proof-of-work anti-bot challenge instead of CSV — confirmed live from both this machine and the device. There is no client-side fix: solving it needs a JS engine, which this app has none of by design (§0's WebView is 2017-era and unused). The `MarketProvider` interface did its job — the card shows "Stooq changed its CSV" instead of garbage or a crash — but the no-key US fallback is gone. Finnhub (needs a key) is now the only working US source, and its own `/stock/candle` is separately paywalled per §5.2 — quotes still work, history does not without a paid key. |

⚠️ **Flagging this plainly:** there is no free, officially supported Vietnam market API. The
VN adapters will break at some point — that is a property of the data source, not the code.
The `MarketProvider` interface exists so a broken provider is a swap, not a rewrite. Each
widget shows a "stale / unavailable" state rather than failing silently, and every provider
carries `attribution` for terms compliance.

**Live-verified on-device, 2026-07-26:** Binance (BTC/USDT, ETH/USDT — live price, live
sparkline, auto-refresh confirmed by watching the price change between two screenshots minutes
apart) and VNDirect/TCBS (VN30 — live price, dashed down-stroke, `⚠ unoff.` label rendering
correctly) both work end to end. Candle chart and timeframe switching (`1H`–`1Y`) verified on
BTC/USDT. **AAPL showed the Stooq failure above, not a Finnhub failure** — no `FINNHUB_API_KEY`
was ever imported during this test, so Finnhub was skipped for lack of a key and Stooq was the
only fallback in the chain; with a real Finnhub key, AAPL quotes should still work today (only
its candle history is paywalled).

**Rendering** (see design.md): custom `Canvas` sparklines and candles. No chart library —
they all animate. Distinguish series by **stroke pattern** (solid / dashed / dotted), never
by colour, since colour dithers to grey.

**Refresh cadence:** manual pull-to-refresh + auto every 5 min while the tab is visible and
the screen is on; never in the background. Respects rate limits, battery, and the panel.

---

## 6. Feature 3 — Draggable floating action menu

An iOS/Samsung-style puck the user drags anywhere, tapped to fan out a menu.

### 6.1 Confirmed menu items

| Item | Implementation | Notes |
|---|---|---|
| **On-screen keyboard** | Custom in-app keyboard view + terminal key row | **Key row: still strongly justified** — no IME on any Android produces Esc/Tab/Ctrl/Alt/arrows, so the terminal cannot work without it. **Full Latin keyboard: now weakly justified.** This entry originally rested on the only IME being the Chinese `SogouIME`; that is gone and the device now has **Simple Keyboard**, a working Latin IME. Phase 1 = key row. The Latin block and the system-wide `InputMethodService` are open decisions rather than planned work. |
| **AI chat (BYOK)** | Opens `:feature-ai` | Any OpenAI-compatible base URL + Anthropic. Config per §7.4. Streaming responses are **buffered and flushed in chunks** — token-by-token rendering is unusable at 16 fps. |
| **Light / dark theme** | Swap `EinkTheme` tokens, recreate views | ⚠️ Dark mode on e-ink drives most pixels black: worse ghosting, slower full refresh, and with **no frontlight on this device** it is harder to read in dim light. Ship it, default **off**. |
| **Rotate 90° CW** | `setRequestedOrientation()` cycling P → L → revP → revL | **Must be app-owned** — verified that `adb settings put user_rotation` does not move a portrait-locked activity. Re-negotiates PTY size on change. |

### 6.2 Suggested additions

You said you hadn't settled the rest. Ranked by value on *this* hardware:

1. **Force full refresh (flash)** — highest value. Clears ghosting on demand; the OEM's own
   status bar has this button, so the expectation already exists.
2. **Quick capture** — one tap to a new task/note without leaving the current screen.
3. **Keep awake** — pin the screen on while reading terminal output. E-ink readers sleep fast.
4. **Terminal snippets** — a palette of saved commands; typing long commands on e-ink is painful.
5. **Clipboard history** — last N copies, since cross-app copy/paste here is clumsy.
6. **Font size cycle** — step the type scale; the single most requested e-ink control.
7. **Screenshot** — capture + share to Telegram.
8. **Toggle file sidebar** — faster than reaching the edge.
9. **Sync now** — force a Telegram poll and market refresh.

Dropped: **brightness/frontlight** — no readable `/sys/class/backlight` or
`/sys/class/leds`, so there is nothing to control.

### 6.3 Two implementation modes

| Mode | Scope | Permission | Phase |
|---|---|---|---|
| In-app overlay — a `View` in the app's own window | Inside InkDeck only | none | **Phase 1** |
| System overlay — `TYPE_APPLICATION_OVERLAY` | Every app on the device | `SYSTEM_ALERT_WINDOW` + `Settings.canDrawOverlays()` consent | Phase 2, opt-in |

`SYSTEM_ALERT_WINDOW` is confirmed available. Start in-app: no permission friction, and the
drag/refresh behaviour gets proven before it can interfere with other apps.

### 6.4 Interaction spec

- Puck **56 dp** (74 px), high-contrast ring, drag with edge snapping, remembers position.
- Idle → collapses to a **32 dp** half-hidden edge tab so it stops occupying the page.
- Tap → menu expands as a **grid or arc away from the nearest edge** (a full radial menu
  clips in corners). Labelled icons — icon-only menus fail on a grey panel.
- **No animation.** The menu appears in one repaint. Fanning it out at 16 fps looks broken.
- Long-press puck → jump straight to full refresh (the most frequent action).
- Drag is testable from the harness: `einknav swipe -X 700 -Y 500 -X2 100 -Y2 900 -Duration 600`.

---

## 7. Feature 4 — Telegram bot as a remote control

Push config and content **into** the device from Telegram.

### 7.1 Transport: long polling (forced, not chosen)

**There is no GMS on this device** — no `com.google.android.gms`, no `gsf`, no Play Store.
So **FCM push is impossible**, and the device is behind NAT so a webhook cannot reach it.

The app is therefore itself the bot client, calling `getUpdates` with an offset. This needs
no public IP and no server.

**Forced, but it turns out to be an advantage here.** The offset is persisted and Telegram holds
a 24 h backlog, so commands sent while the tablet is asleep or force-stopped are **not lost** —
they arrive in one burst the next time the process runs. That is materially better than push
would have been on a device that spends most of its life stopped (§5.1b-2), and it is why
Telegram, not the local notification, is now the primary reminder channel (§5.1d).

### 7.2 Command set

**As built (Phase 5) vs. as originally sketched — the two differ, deliberately.**

| Command | Effect |
|---|---|
| `/pair <code>` | Complete pairing — the first chat to send the 6-digit code shown in-app becomes the allowlisted chat. Not in the original sketch; it is the onboarding step every other command depends on |
| `/llm <provider> <base_url> <model> <api_key>` | Register/replace a BYOK LLM profile |
| `/key <name> <value>` | Store a secret in the vault inline — **not** a document attachment as first sketched; see below |
| `/task add <title> \| <due> \| <repeat>` | Create a task. `<due>` parses `today 14:00`, `tomorrow 9am`, `2026-07-30 14:00`, `14:00`, `fri 18:00`, `30/07 14:00`, `0930`, plus a trailing zone (`UTC`, `Z`, `Asia/Tokyo`, `+07:00`) per §5.1c |
| `/task list [today\|week\|overdue]` · `/task done <id>` · `/task del <id>` | Task CRUD |
| `/note <text>` | Create a task with no due date |
| `/status` | Battery, network, open/overdue task counts, next task, paired chat id, vault protection mode, auto-delete state |
| `/help` | Command list |

**Sketched but not built, and why:** `/llm list` · `/llm use <name>` · `/host add` · `/host list`
· `/watch add <market> <symbol>` · `/refresh`. Every one of them reaches into a module the
Telegram implementation was scoped away from touching (`:feature-market` did not exist yet when
Phase 5 was written; hosts are `HostStore` in `:core-data` but the semantics belong to
`:feature-terminal`; `/refresh` needs the Activity's `EinkRefresher`). An unknown command gets a
usage reply rather than being silently ignored, so these fail loudly, not quietly — closing them
is app-module wiring, not a Telegram-module change.

`/key` takes the secret **inline** (`/key finnhub AbCd1234`) rather than as a document upload as
first sketched. §7.4 already says not to send the `.pem` this way regardless; a file upload would
also need `getFile` plus a second HTTP round trip for no benefit `/key` doesn't already give.

Parsing is strict and every command replies with a confirmation or a specific error — a
silent failure on a device you are not holding is the worst outcome. **Pre-pairing, `/pair` is
the only command any chat can run** — everything else from an unpaired chat is dropped with no
reply at all, so a stranger probing the bot cannot even confirm it exists.

### 7.3 ~~Adaptive polling~~ — struck; not implementable on this device

The table below was drafted before §5.1b. Every row except the first presupposes a **timer to
wake on**, and this package is granted none: `AlarmManager` is refused outright, and
`JobScheduler` is cancelled by the same force-stop sweep. There is no mechanism that fires at
"5 min" while the process is not running, and while the process *is* running there is no reason
to poll slower than the long-poll allows.

~~| Screen on, foreground | long-poll `timeout=50` | Screen on, background | 60 s | Screen off,
charging | 5 min | Screen off, on battery | 15 min |~~

**What the device actually offers is two states**, and the settings screen must say so rather
than reporting an interval it cannot honour:

| State | Behaviour |
|---|---|
| Process alive (foreground service running) | `getUpdates` with `timeout=50`, backoff 5 s → ×2 → 5 min cap on network failure |
| Process not running | Nothing. Commands queue on Telegram's side for 24 h and arrive in a burst on next start |

An *empty* long-poll must count as success. Treating "no updates" as a failure backs a perfectly
healthy idle connection off to the 5 min cap within a few minutes.

### 7.4 ⚠️ Security: sending API keys over Telegram

You asked to send **API key + base URL + model name** through the bot. That works, and it is
genuinely convenient — but be clear-eyed about what it costs:

- The message travels through and is **stored on Telegram's servers**, in a chat history that
  is not end-to-end encrypted (regular bot chats never are).
- It stays in the history on **every device signed into that account** until deleted.
- Anyone who gets that account gets your keys.

**Mitigations the app will implement:**

1. **Auto-delete on ingest.** After parsing `/llm`, the bot immediately calls `deleteMessage`
   on the user's message, then replies with a redacted confirmation (`sk-…7f3a`). Shrinks the
   exposure window to seconds.

   **The failure contract matters as much as the mitigation.** A delete that fails silently is
   worse than no auto-delete at all, because the user then believes the key is gone and does not
   rotate it. So: a failed delete is stated in the reply, in capitals, with instructions to
   rotate; auto-delete switched *off* produces an explicit warning in the reply rather than a
   plain confirmation; and the delete fires on the malformed-input paths too, since a mistyped
   `/llm` still contains the key.
2. **`chat_id` allowlist.** Only your paired chat is honoured; everything else is dropped and
   logged. Pairing uses a one-time code shown on the device.
3. **Bot token in the same encrypted vault** as the SSH keys (§4.3).
4. **Preferred alternative offered in-app:** enter keys via the on-screen keyboard, or a
   LAN pairing flow (device shows a QR / short code, desktop posts the config directly over
   the local network) — no third party involved.
5. **In-app warning** on the Telegram settings screen stating the above in one sentence.
6. Treat any key sent this way as **rotatable**: use provider keys scoped and cheap to revoke.

My recommendation: keep `/llm` for convenience, rely on auto-delete, and use scoped keys you
can rotate freely. Do not send anything through Telegram that you could not revoke in a
minute — and given §4.2, **do not send the trading-server `.pem` this way**; push it over
USB with `adb push` instead. `/key` will accept it, but the plan flags it as the wrong
channel for that particular secret.

---

## 8. Feature 5 — Design documentation

[design.md](design.md) covers, per screen: ASCII layout at true dp proportions, typography
scale with measured contrast ratios, component specs, spacing grid, refresh classification
(partial vs flush), and the empty/loading/error/offline states.

---

## 9. Delivery phases

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Harness** ✅ | `einknav.ps1`, device baseline, OEM UI survey | `probe`/`look` working — **done** |
| **1. Skeleton** | Project setup, `:core-eink` theme + refresh policy, shell + tabs, paged scroll | APK installs; flush trick visibly clears ghosting; zero animations |
| **2. Terminal** | jsch + vt320 + `TerminalView`, host config parser, vault, key row | Interactive session to `t4-aws-binh` with the on-device `.pem`; `vim` usable |
| **3. Files** | SFTP browser, sidebar drawer / landscape split, transfers | Browse, up/download, "cd here" |
| **4. Tasks** ⚠️ | Room, CRUD, alarms, local notifications, boot re-arm | Built and working, but the exit criterion **cannot be met on this ROM**: `AlarmManager` refuses every alarm from this package, so nothing can wake the device. Delivery works via a polling ticker while the process lives — §5.1b |
| **5. Telegram** ✅⚠️ | Foreground service, long-poll, command router, pairing, auto-delete, outbound queue | Built; poll loop verified live against `api.telegram.org` for 2+ min with no TLS error. **`/pair` round-trip and the rest of the command set are unverified** — completing pairing needs the owner's own Telegram client, which this environment does not have |
| **6. Floating menu** ✅ | In-app puck, drag + snap, menu, flush/rotate/theme/keyboard | Drag across screen; rotate re-sizes PTY — **done**, plus a real bug found and fixed post-ship (see §6.4a) |
| **7. Market** ✅ | Provider adapters, widget grid + picker, Canvas charts | Crypto **live-verified** (BTC/ETH via Binance); VN **live-verified** with the `⚠ unoff.` label showing; candle chart and timeframe switching **live-verified**. US-stocks-no-key (Stooq) is live-broken, not a code defect — see §5.2 |
| **8. AI chat** ✅⚠️ | BYOK profiles, chunked streaming, history | UI, seeded Anthropic profile and settings screen verified on-device. **Streaming is unverified** — needs a real Anthropic/OpenAI-compatible key, which was not supplied |
| **9. Polish** | Full-refresh tuning, battery profiling, font sizes, error states | 8 h idle drain measured; no ANRs; ghost budget tuned on-device |

Phases 2 and 4 are independent — either can follow Phase 1.

### 6.4a Bug found integrating Phase 5 into the shell: a full-screen fragment fought the tab switcher

`TelegramSettingsFragment` was first attached to `R.id.content` — this Activity's own tab-
switching container (see §3.2 pattern) — via `add()` + `addToBackStack()`. It worked until the
user left it by tapping the `TabBar` instead of pressing Back: `selectTab()` only knows about the
four tab fragments, so it neither hid the settings fragment nor accounted for it, and each first
visit to a tab adds that tab's fragment as a new, later — therefore higher — sibling, silently
burying Telegram settings underneath while its view stayed fully attached, clickable, and present
to `uiautomator`, invisible in a screenshot but still there. Confirmed by probing the accessibility
tree while a screenshot showed a different tab entirely.

Fixed by attaching it to **`android.R.id.content`** instead — the window's own root, the same
container `FloatingMenu` already uses. That covers the tab bar too, so there is nothing left for
a tab tap to reach, and it is guarded against a second push if the floating menu is tapped again
while it is already open. Verified: tab bar taps are now fully blocked while settings is open,
and Back closes it cleanly with no residue.

---

## 10. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **ROM refuses alarms and force-stops the app** | **High** — confirmed, not a risk any more | Polling ticker in a foreground service (§5.1b). Cannot wake from deep sleep; the only real fix is getting the package onto the vendor's freeze whitelist, which is a device-settings action |
| VN market endpoints are unofficial and will break | **High** | `MarketProvider` abstraction; explicit stale/unavailable states; §5.2 flagged |
| API keys exposed via Telegram history | **High** | Auto-delete, allowlist, scoped keys, LAN alternative (§7.4) |
| Host-key checking disabled → MITM on the trading server | **High** | TOFU pinning by default (§4.2) |
| 550 MB free RAM; terminal scrollback + charts + service | Medium | Memory-pressure-capped scrollback; single SSH session shared with SFTP; no bitmap caches |
| Ghosting makes the UI feel broken | Medium | Ghost budget + flush trick + manual flush; verified with `watch` |
| Aggressive sleep kills the SSH session / poller | **High** — the battery exemption was tested in Phase 4 and does nothing here | Foreground service, `ServerAliveInterval`, auto-reconnect. Reconnect-on-resume has to be the design, not a fallback |
| TLS failures against modern endpoints on an API 27 CA store | Medium | Conscrypt + bundled trust store (§2.4) |
| 32-bit armv7 only — a dependency ships arm64-only natives | Medium | Prefer pure-Java (jsch, vt320); audit ABIs in CI |
| Custom keyboard is a large surface | Medium | Phase it: key row → Latin → optional IME |
| No root → no true waveform control | Low | Accepted; indirect flush is sufficient in practice |

---

## 11. Non-goals (v1)

Play Store release · multi-device sync · a hosted backend · order execution or any
brokerage write path (**read-only market data only**) · PDF/EPUB reading (the device already
has readers) · arm64 · tablets other than the InkReader 6.

---

## 12. Open decisions

1. **Your item 6 was left blank** — the message ends at `6.` with no content. What was it?
   Still genuinely open; nothing since has resolved it.
2. **Floating menu final list** — ~~confirm which of §6.2's nine suggestions to build in Phase
   6~~ **partially resolved.** Built: Flush, Quick (task capture), Awake, Files (sidebar toggle).
   The 9th cell went to Telegram settings instead of `More`'s original candidates — see §11.3.
   Still undecided: Terminal snippets, Clipboard history, Font size cycle, Screenshot, Sync now.
3. **System-wide overlay** — still open; the floating menu shipped in-app only (§6.3).
4. **VN data source** — ✅ **resolved by building it.** VNDirect + TCBS adapters shipped and are
   **live-verified working** as of 2026-07-26, `⚠ unoff.` label showing correctly. The fragility
   warning stands regardless — see §5.2's Stooq entry for what "will break at some point" looks
   like in practice, on a *different* provider than the one flagged.
5. **Full IME** — still open. Terminal key row + Simple Keyboard (stock IME) is what shipped;
   no in-app Latin keyboard was built.
6. **Terminal-only fallback** — moot. All phases through 8 are built; there is no partial-ship
   question left to answer.
7. **Language** — ✅ **resolved, early:** *"Chỉ dùng tiếng Anh"* — English-only UI. Every screen
   built since honours this; this entry should have been struck long before Phase 4.
