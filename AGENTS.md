# AGENTS.md — shared conventions for anyone (human or AI) touching this repo

This file collects the things that took more than one Phase to learn, so the next person does
not have to. `HANDOFF.md` is the read-once; this is the read-when-something-is-weird.

## Before you write code

1. **Read `HANDOFF.md`, then `Plan.md` (whole file), then `design.md` (whole file).** The spec is
   the spec. If you deviate, say so in the docs and say why, not just in commit messages.
2. **Read the source of anything in `:core-eink` you are about to use.** Those widgets and
   helpers were written for this panel and the comments explain constraints you will otherwise
   rediscover the hard way.
3. **If you are scoped to one module, read `docs/AGENT_BRIEF.md`.** It restates the shared
   non-negotiables in a shorter form.

## Hard constraints (still 100% in force after Phase 9)

The full list is in `HANDOFF.md` §"Hard constraints". The three that bite the most:

- **No `AlarmManager` for anything.** This package is frozen by the OEM launcher; every `set*`
  call is silently dropped. Use `WorkManager` or, more usually, a polling ticker in a
  foreground service. The reminders path is `ReminderTicker`; polling cycles are
  `ReminderTicker` and `TelegramService`.
- **`SecretVault(context)` is forbidden outside `:core-data`.** Use `SecretVault.get(context)`.
  Five modules each wrote their own constructor once; only the singleton holds the unlocked
  data key across the process.
- **Canvas-drawn text needs `contentDescription` in code.** Has bitten us twice (TalkBack and
  `uiautomator` see nothing otherwise). Every custom view that draws its own label is
  responsible for setting it.

## Refresh classification (design.md §13)

- `[F]` = full flush. `refresher.flush("reason")`.
- `[P]` = partial. `refresher.notePartial(surface, "reason")` and let the ghost budget
  decide. The return value is `true` when the budget tripped and a flush already happened;
  ignore it unless you have a reason not to.
- New interactions must be classified in `design.md` §13 when added.

## Refresh policy knobs

- **Ghost budget N** lives in `EinkRefresher.DEFAULT_GHOST_BUDGET = 8`. Tune on the device with
  `einknav watch`. It is `var` on the constructor; `MainActivity` constructs the refresher
  with the default. The `EinkLabActivity` (debug build only) is the right place to test
  changes before shipping.
- **Log gate** (Phase 9 item 1): only the two partials before the trip are logged at
  `Log.d`; the rest are `Log.v` in debug only. If your fix is being drowned by
  `InkDeckRefresh`, re-read `core-eink/.../refresh/EinkRefresher.kt:41-50` — the per-partial
  log was the single biggest logcat noise source.
- **Flush broadcast** (Phase 9 item 4): `BroadcastFlush` posts the `sendBroadcast` to the
  main `Handler`. Do not undo that.

## E-ink drawing rules (design.md §2, §14)

- No animations. No transitions, ripples, fades, slides, spinners, overscroll glow.
- No fling / momentum scrolling — use `EinkRecyclerView` or `EinkScrollView`, both of which
  already disable fling. For long scrolls use the §5.5 paged rail.
- No text below 14 sp. No weight below 400. No italics. No `ink-300`/`ink-200` for text
  (those are borders and dividers only).
- No shadows, elevation, or gradients — they dither into mud.
- Vector drawables only. 212 dpi falls between buckets, bitmaps are scaled and blurred.
- Touch targets ≥ **56 dp** (74 px), not 48 dp. A missed tap on a 16 fps panel is
  indistinguishable from a slow tap; bigger targets fix that.
- Colour never carries meaning alone. Use shape and stroke pattern (solid / dashed / dotted,
  glyphs `▲ ▼ – ! ⌛`).
- Validate contrast on the physical panel, not from a `screencap` PNG. `screencap` reads the
  RGB framebuffer; the panel sees a dithered greyscale of it, and is the only place contrast
  can be confirmed. `einknav look` is a confirmation, never the source of truth.

## Verification loop

```bash
adb -s AA000552A2142900248 install -r app/build/outputs/apk/debug/app-debug.apk
powershell -ExecutionPolicy Bypass -File .\tools\einknav.ps1 probe   # primary
powershell -ExecutionPolicy Bypass -File .\tools\einknav.ps1 look    # confirmation only
powershell -ExecutionPolicy Bypass -File .\tools\einknav.ps1 watch -Count 8 -IntervalMs 1200
```

Run on the **physical device** (adb serial `AA000552A2142900248`), never an emulator. Always
end-to-end with `am force-stop` + relaunch when verifying persistence.

## When the device is offline

If `adb devices` shows `AA000552A2142900248 offline` (it has happened twice in this project's
history), do not fight it — surface the issue to the owner and keep working on whatever is
device-independent: planning, code edits, JVM-testable logic. Re-test the device-bound
behaviour when the device comes back. Document the gap in the commit and the relevant doc
section.

## Module / file ownership

- `:app` — shell, MainActivity, tab switching, floating menu host, theme switching, debug
  `EinkLabActivity` and `GhostPatternView`. Owned by the coordinator, not a sub-agent.
- `:core-eink` — refresh policy, theme tokens, base widgets, paged scrolling, debug
  `IdleProbe`. Sub-agents may consume but should not modify without explicit go-ahead.
- `:core-data` — Room, `SecretVault.get(context)`, `HostStore`, `KnownHostsStore`. Touching
  the schema is a `v → v+1` migration with the JSON checked in.
- `:core-net` — OkHttp + Conscrypt, TLS config, retry/backoff. Shared by every network call.
- `:feature-*` — owned by the per-feature sub-agent, the coordinator integrates.
- `tools/einknav.ps1`, `tools/restore-oem-apps.ps1`, `tools/einkrefresh.ps1` — the harness.
  Do not reformat or "tidy". Do not modify the device-state restoration script without
  telling the owner first.

## Style

- Match the surrounding code. In particular: comments explain **why**, especially where the
  code looks odd because the device forced it.
- **DO NOT add comments** that restate the code. The valuable comment is the one that records
  a measurement or a rejected alternative.
- UI is English only. (`"Chỉ dùng tiếng Anh"`.)
- No new Gradle dependencies without checking the catalog first — `libs.versions.toml` is
  pinned to the local cache; the right thing to do is solve it with what is there.

## Definition of done for a feature

`./gradlew :your-module:assembleDebug` passes. The thing compiles, and `einknav probe/look`
against the live device shows the expected tree and contrast. Test typing by tapping the
on-screen keyboard, not by `adb shell input text` (which bypasses the IME and missed the
`TYPE_NULL` bug once). For long-running services, verify `onDestroy` runs cleanly via
`am force-stop` + logcat.

## Open follow-ups as of Phase 9

- `SshSession` hoist into a `ViewModel` so the dark-theme recreate stops dropping a live
  session (Plan.md §12 item 8). Phase 9 only persisted the toggle; the recreate still kills
  the SSH session unless the user agrees to switch.
- The 8 h idle drain measurement itself — Phase 9 shipped the harness, the number is owner-
  driven. `adb -s … logcat -d -s InkDeckIdle -v time > idle.log` after an 8 h shift.
- The `12.0` items in `HANDOFF.md` are still genuinely open. `FloatingMenu` is at 10 cells
  (3×3 + 1) and the owner may want to retire one if 3×3 is non-negotiable.
- Phase 9 added the `Aa` cell without retiring anything. The grid now scrolls.
