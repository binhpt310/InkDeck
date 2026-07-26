# InkDeck

An SSH terminal, task manager, market dashboard and BYOK AI chat for the **InkReader 6**
(`EPD106A`) — a 758 × 1024, **16 fps**, grayscale e-ink Android tablet.

Built as a native Kotlin app with classic XML Views, because at 16 fps every default Android
motion affordance reads as a fault. There are no animations anywhere in this app: no ripples, no
transitions, no fling scrolling, no spinners. That constraint drives most of the design.

- [Plan.md](Plan.md) — architecture, feature specs, delivery phases, verified device baseline
- [design.md](design.md) — per-screen layouts, type scale, contrast tokens, component specs

## Status

| Phase | State |
|---|---|
| 0. Harness | ✅ `tools/einknav.ps1` |
| 1. Skeleton | ✅ theme, refresh policy, shell, paged scrolling |
| 2. Terminal | ✅ SSH session, vault, key row, TOFU host keys, IME input |
| 3. Files | ✅ SFTP browser, portrait drawer / landscape split, transfers, file viewer |
| 4. Tasks | ✅ Room, CRUD, repeat rules, per-task UTC, notifications, boot re-arm — ⚠️ delivery is a polling ticker, not `AlarmManager`; see [Plan.md §5.1b](Plan.md) |
| 6. Floating menu | ✅ draggable puck, edge snap, 3×3 grid, long-press flush |
| 5, 7–9 | ⬜ Telegram, market, AI chat, polish |

## Requirements

- **JDK 17+** — AGP 8.13 will not run on 11
- Android SDK with platform 36 and build-tools 36.1.0
- The device, over USB. There is no emulator story here; the whole design depends on real panel
  behaviour, and screenshots cannot show it (see below)

```bash
./gradlew :app:assembleDebug
```

`gradle.properties` pins `org.gradle.java.home`. Change it to your JDK 17 path.

## What is unusual about this target

Worth reading before changing anything, because several of these look like bugs in the code:

- **Screenshots lie.** `screencap` reads the RGB framebuffer, so colour appears as colour in a
  PNG and dithers to grey on the panel — and a waveform flush is byte-identical before and
  after. Never validate contrast or refresh behaviour from a screenshot.
- **The window reports two different sizes.** `mFrame` is 758 × 1024 with a 56 px top inset, but
  `appBounds` is 758 × 960. Layouts get measured against 968 px and laid out into 904 px, and
  anything below a weighted child falls off the screen. Use `Configuration.screenHeightDp`
  (682 dp) via `EinkGeometry`, and `AppBoundsLinearLayout` as the root of full-screen layouts.
- **The panel flush is an undocumented broadcast.** `android.eink.force.refresh` visibly flushes
  the display, despite `dumpsys` showing zero registered receivers for it. An empty resolver
  table is not evidence of absence on this device.
- **The keystore cannot hold a key.** AES and RSA key generation both throw `ProviderException`,
  so the vault falls back to a key file and says so in the UI.
- **A sleeping device breaks both inspection channels** with misleading errors — `uiautomator`
  returns "null root node" and `screencap` returns solid black. `einknav` wakes the device first.

## Security posture

- SSH private keys live encrypted (AES-256-GCM) in app-internal storage, never `/sdcard`.
- Host keys are **trust-on-first-use pinned**. An `ssh_config` asking for
  `StrictHostKeyChecking no` is imported as TOFU instead, and the import says so — it stays
  available per host, but is never what an import silently applies.
- The vault opens without a passphrase by default (single-user device). The passphrase toggle
  swaps the wrap rather than adding one, so the mode shown is the protection you have.
- Push `.pem` files over USB, never through Telegram. See Plan.md §7.4.

## Licence

**GPL-2.0-or-later** — see [LICENSE](LICENSE).

Not a free choice: InkDeck vendors ConnectBot's `de.mud.terminal` VT emulator, which is
GPL-2.0-or-later, and linking it makes the whole app a derivative work. Provenance and
per-file licences are in
[`feature-terminal/src/main/java/de/mud/terminal/NOTICE.md`](feature-terminal/src/main/java/de/mud/terminal/NOTICE.md).

Relicensing under something permissive would mean replacing that emulator — Termux's
`terminal-emulator` (Apache-2.0) is the realistic candidate, and it is not a drop-in.
