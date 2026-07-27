# Continuation Handoff Prompt

Copy everything below the line into a new AI coding agent conversation opened in
`D:\Claude_workspace\einkscreen_project`.

---

## Project

**InkDeck** — a native Android app for an **InkReader 6** e-ink tablet (`EPD106A`), physically
connected over USB/adb. This is not a fresh start: **Phases 0–8 are built**, most of it
live-verified on the device. You are picking up mid-project, not bootstrapping it.

Read these first, in this order. They are the spec and the record of everything learned since —
do not re-derive what is already in them, and do not re-probe device facts that are already
verified.

- **`Plan.md`** — architecture, feature specs, phases, risks, the verified device baseline (§0),
  and a long trail of "found this the hard way" sections (§5.1b, §5.1b-2, §5.1d, §5.1e, §6.4a).
  Read these sections in full before touching scheduling, Telegram, or the vault — they record
  real constraints this hardware imposes that are not obvious from generic Android knowledge.
- **`design.md`** — per-screen ASCII layouts, type scale, contrast tokens, component specs, and
  an "as built" subsection after most screens noting where the shipped version differs from the
  original mock and why.
- **`docs/AGENT_BRIEF.md`** — the brief given to sub-agents that built Phases 5/7/8 in parallel.
  Useful as a fast orientation doc and as the shared-constraints summary if you delegate work.
- **`tools/einknav.ps1`** — working device inspection/navigation harness. Use it; do not rebuild
  it.
- **`tools/restore-oem-apps.ps1`** — records every modification made to the device's system
  state. Do not make more without checking this first and telling the owner.

## Current status — read this before assuming anything is missing

| Phase | State |
|---|---|
| 0. Harness | ✅ done |
| 1. Skeleton | ✅ done |
| 2. Terminal | ✅ done, live-verified (SSH session to a real host, `vim`, key row, IME quirks all resolved) |
| 3. Files | ✅ done, live-verified (SFTP browse, file viewer, nav row) |
| 4. Tasks | ✅ done, live-verified (2×2 board, per-task UTC zone, reminders fire and fall back to a local notification correctly) |
| 5. Telegram | ✅ built, ⚠️ **pairing unverified** — poll loop confirmed live against `api.telegram.org` for 2+ minutes with no TLS error, but completing `/pair <code>` needs a human with a real Telegram client on the other end, which no prior session had |
| 6. Floating menu | ✅ done, live-verified, one real bug found and fixed post-ship (§6.4a) |
| 7. Market | ✅ done, live-verified (Binance BTC/ETH, VN30 via VNDirect/TCBS, candle chart, widget picker) — **Stooq (US, no-key) is live-broken by an anti-bot challenge, not a code defect; see Plan.md §5.2** |
| 8. AI chat | ✅ built, ⚠️ **streaming unverified** — needs a real Anthropic or OpenAI-compatible API key, which was never supplied |
| 9. Polish | ✅ done. Refresh log gate, BroadcastFlush off the UI thread, dark theme persistence, font-size cycle (`Aa` floating-menu cell, 11–17 sp, wraps 17→11, persisted in `inkdeck.terminal.xml`), 4-state sweep on `FilesView` + `FileViewerView`, Tasks first-emit `StepBar`, `IdleProbe` (8 h drain harness). All installed and verified on the InkReader 6. 8 h measurement itself is owner-driven (the harness is the missing piece and it is now shipped). See Plan.md §9.0. |

**If you are not doing Phase 9, the two highest-value things you can do are:** get the owner to
send `/pair <code>` to their bot to unblock Telegram command testing, and get a real AI API key
into the vault to unblock streaming verification. Everything else in Phases 0–9 is built and
either verified or has a documented reason it couldn't be.

## Phase 9 — what was done and what still needs the owner

Phase 9 shipped as **8 items in Plan.md §9.0**, compiled, installed on the InkReader 6, and
spot-verified on the device. The 8 h idle drain measurement itself is owner-driven — the
harness is in place but the measurement needs the device on a charger for eight hours and a
human to read the result.

Verified live on the device during this session (2026-07-27):

| Item | What was verified |
|---|---|
| 1. `EinkRefresher` log gate | `adb logcat -s InkDeckRefresh` shows only `FLUSH #N` + `flush strategy=broadcast` lines, no `Log.v` partial spam. |
| 3. Font-size cycle | Tapped the `Aa` cell 6 times via `einknav tap`; `inkdeck.terminal.xml` shows `inkdeck.term.font_sp=12.0`; value persists across `am force-stop` + relaunch; cycle wraps 17→11. |
| 4. `BroadcastFlush` off UI thread | ~80 ms gap between the `FLUSH` log and the `flush strategy=broadcast` log is the `Handler.post` working. |
| 5. Dark theme persistence | Toggled Theme, `inkdeck.theme.xml` created with `inkdeck.theme.dark=true`; `force-stop` + relaunch restores dark; only one flush after launch (no double-recreate). |
| 8. `IdleProbe` harness | `adb logcat -d -s InkDeckIdle` shows `activity-resume MainActivity`, `started`, `service-start TelegramService` (because Telegram was enabled) — single-tag stream the 8 h drain needs. |

Code-complete but not live-observed (because the device interaction required is owner-only):

| Item | What needs the owner |
|---|---|
| 2. 4-state `EmptyStateView` on `FilesView`/`FileViewerView` | The empty/error/offline states are reached only by opening the file browser during a live SSH session. Code paths are in place. |
| 6. `InkDeckTg` log demotions | Code edits in place; live verification needs `/pair` to happen, then a few hours of idle with the bot running. |
| 7. Tasks first-emit `StepBar` | Visible only for ~300 ms on first paint of the Tasks tab; the `einknav probe` cycle is too slow to catch it. Code is in place. |

**The 8 h drain measurement itself:**

```bash
adb -s AA000552A2142900248 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s AA000552A2142900248 logcat -c
# leave the device on a charger with the app foreground for 8 h
adb -s AA000552A2142900248 logcat -d -s InkDeckIdle -v time > idle.log
```

`idle.log` will have `started`, `battery pct=…` once per hour, `service-start` / `service-stop`
each time a foreground service crosses a lifecycle boundary, and `screen-on` / `screen-off`.
Phase 10 (or whenever) can take that file and produce an actual drain number.

## Device facts that override default Android assumptions

These are expensive to re-discover. Full detail and how each was found is in `Plan.md` §0 and
the sections it cross-references — read those before you disbelieve any of these.

| Fact | Why it matters |
|---|---|
| **`AlarmManager` is refused for this app, silently.** The OEM launcher marks new packages "frozen"; a patched framework drops every `set*` call from a frozen package. No exception, no entry in `dumpsys alarm`. Confirmed: only `android` and the vendor OTA app hold alarms on this device. | **Never use `AlarmManager` for anything.** Reminders/polling use a foreground-service ticker instead (`ReminderTicker`, `Plan.md` §5.1b). If you're building anything time-based, this is why. |
| **The OEM force-stops the app ~30 s after it backgrounds**, even with a foreground service running (measured killed at `adj 200` with the Activity still alive). | Anything long-running needs `onResume`-driven reconnect as the *design*, not a fallback — see `TelegramGraph.startIfEnabled` and its call site in `MainActivity.onResume`. |
| **`Configuration.screenHeightDp` is 682 dp, not what the display metrics suggest.** A phantom 64 px nav strip is in the window frame but excluded from `appBounds`. | Use `EinkGeometry`/`AppBoundsLinearLayout`, never raw display height, for any full-screen layout. |
| **Android Keystore cannot hold a key on this device** — both AES and RSA `KeyGenParameterSpec` throw `ProviderException`. | The vault (`SecretVault`) falls back to a device-key file automatically; this is expected, not a bug to chase. |
| **`SecretVault` must be a process-wide singleton.** Five modules once each constructed their own instance; fixed with `SecretVault.get(context)`. | **Never call the `SecretVault` constructor directly.** Always `SecretVault.get(context)`. |
| **A full-screen fragment that belongs to no tab must attach to `android.R.id.content`, not this Activity's own `R.id.content`** (the tab-switching container). | See `design.md` §6.1 and `Plan.md` §6.4a for the bug this caused and the fix. Follow this for any new full-screen screen. |
| Panel is **16 fps grayscale, no frontlight, reflective**. | Zero animation anywhere, ever. This is not a style preference — it is enforced throughout `design.md` §14. |
| No Google Play Services, no FCM. | Telegram is long-poll only, and turns out to be the *primary* reminder channel, not a nice-to-have — see `Plan.md` §5.1d. |
| WebView is Chromium 61 (2017), unused and unusable. | Never reach for a WebView-based solution to anything, including "just render this HTML". |

## Architecture

```
:app                 shell, MainActivity, tab switching, floating menu host
:core-eink           refresh policy (EinkRefresher), EinkTheme, base widgets, paged scrolling
:core-data           Room, SecretVault (single process-wide instance), HostStore, KnownHostsStore
:core-net            OkHttp + Conscrypt (TLS on API 27), InkHttp.client
:feature-terminal    SSH session (jsch), vt320 terminal, SFTP browser, file viewer, vault setup UI
:feature-tasks       Room task model, 2×2 board, ReminderTicker, ReminderDelivery (route list)
:feature-telegram    long-poll service, command router, pairing, OutboundQueue, settings screen
:feature-market      MarketProvider adapters (Binance/Finnhub/Stooq/VNDirect/TCBS), widget grid
:feature-ai          BYOK provider abstraction (Anthropic + OpenAI-compatible), ChunkBuffer, chat UI
```

`:feature-telegram` depends on `:feature-tasks` (one-way) so it can register a reminder delivery
route without `:feature-tasks` ever knowing Telegram exists — see `ReminderDelivery` in
`:feature-tasks` and `TelegramGraph.registerReminderRoute`. Follow this pattern if you wire
another module into the reminder path.

## Known issues, in order of what to do about them

1. **Telegram pairing is unverified.** Enable Telegram in the app (floating menu → the
   paper-plane icon), read the 6-digit code, and have the owner send `/pair <code>` to their bot
   from their own phone. Then verify `/task add`, `/status`, `/llm`, `/key` round-trip and the
   auto-delete behavior on `/llm`/`/key`.
2. **AI streaming is unverified.** Get a real Anthropic or OpenAI-compatible key into the vault
   (via the AI providers settings screen, or `/llm` once Telegram is paired) and send a message.
   Watch for the ≤2 Hz chunk cadence actually holding on-device, not just in code review.
3. **Stooq (US, no-key market fallback) is dead** — it now serves a JS anti-bot proof-of-work
   challenge instead of CSV. This is not fixable client-side (would need a JS engine, which this
   app deliberately has none of). Leave it; a Finnhub key is the only remaining US quote source.
   Do not spend time trying to "fix" Stooq parsing.
4. **Phase 9 (Polish) has not been started at all.** Full-refresh tuning, battery/idle-drain
   measurement, font-size cycling, and a sweep of every screen's four states (loading/empty/
   error/offline per `design.md` §5.7) are all open.
5. **`Plan.md` §12 has a few genuinely open decisions left**: item 1 (a blank item 6 from the
   original brief — nobody knows what it was), item 2 (five of nine floating-menu proposals
   still unbuilt: snippets, clipboard history, font-size cycle, screenshot, sync-now), item 3
   (system-wide `SYSTEM_ALERT_WINDOW` overlay vs. in-app only), item 5 (a full system-wide IME
   vs. the terminal key row, which already works). Ask the owner rather than guessing on these.

## Hard constraints (unchanged, still 100% in force)

1. No animations, anywhere. 16 fps panel.
2. No text below 14 sp, no font weight below 400, no italics.
3. Text only in `ink-900`/`ink-700`/`ink-500`. `ink-300`/`ink-200` are borders and dividers only.
4. Touch targets ≥ **56 dp** (74 px) — not the Android-standard 48 dp; see `design.md` §4 for why.
5. Vector drawables only — 212 dpi falls between density buckets.
6. Colour never carries meaning alone (panel is grayscale); use shape and stroke pattern.
7. No shadows, elevation, or gradients.
8. Every native dependency must ship `armeabi-v7a` (32-bit only, no arm64).
9. Never call `SecretVault(context)` directly — always `SecretVault.get(context)`.
10. Never use `AlarmManager` for anything on this device — it is silently refused.
11. Screenshots capture the RGB framebuffer, **not** panel output. Never validate contrast, or
    whether an `android.eink.force.refresh` broadcast actually flushed the panel, from a
    screenshot. A human has to look at the physical panel for that.
12. Canvas-drawn labels need an explicit `contentDescription` set in code — this has bitten the
    project twice (TalkBack/`uiautomator` see nothing otherwise).

## Verification loop

```bash
powershell -ExecutionPolicy Bypass -File .\tools\einknav.ps1 probe
```

`probe` (uiautomator dump → text + tap coordinates) is the reliable channel; `look` (screenshot)
is confirmation only and cannot show e-ink flush behavior. After each install: `adb install -r`,
then `probe` to assert the tree, `look` to eyeball layout, and
`watch -Count 8 -IntervalMs 1200` across a transition to inspect ghosting.

Run on the **physical device** (adb serial `AA000552A2142900248`), never an emulator.

## Secrets and repo hygiene

- Real secrets live in `.env` (gitignored). `.env.example` is the **only** env file meant to be
  committed — it must stay a template with empty values. Do not ever put a real token in it.
- The Telegram bot token used during development was pasted into `.env.example` once by mistake
  and later into chat history — if that token is still live, **rotate it via BotFather** before
  this repo goes anywhere public, if that has not already been done.
- This directory is not yet a git repository. The owner has said it will go open source
  eventually; do not `git init` or make repo-hosting decisions without asking first.

## Ground rules

- `Plan.md` and `design.md` are the spec. If you deviate, say so and why, in the docs, not just
  in chat.
- If you discover a device fact that contradicts them, **update the docs** — this has happened
  repeatedly and is expected, not a failure. Don't let a doc go stale; the owner explicitly
  checks for this.
- Do not modify the device's system state (`pm disable`, `setprop`, uninstalls) without saying so
  first. Every prior change is logged in `tools/restore-oem-apps.ps1`.
- Don't reformat or "tidy" the existing docs and tools.
- The owner communicates in Vietnamese sometimes; answer in Vietnamese when they do.
- Default to English-only UI text — this was decided early and is final.

Start by reading `Plan.md` and `design.md` in full, then tell the owner what you plan to do next
— most likely either Phase 9 polish, or driving the two unverified items (Telegram pairing, AI
key) to completion — before writing code.
