# Shared brief for feature agents

Read this, then `Plan.md` and `design.md` for your phase. Those two are the spec. If you deviate,
say so in the docs and say why — do not silently diverge, and do not reformat or "tidy" existing
docs or `tools/`.

## The target

A physically-connected **InkReader 6** e-ink tablet (`EPD106A`). Not an emulator, not a phone.

| Fact | Consequence |
|---|---|
| E-ink, ~16 fps, ~60 ms latency, grayscale | **Zero animation.** No ripples, no fling, no spinners, no cross-fades, no progress bars that move. |
| 758 × 1024 px, 212 dpi, content area 572 × 682 dp portrait | Very little room. Chrome costs 56 dp a bar. |
| Five-step grey ramp only | **Colour never carries meaning** — encode state as shape, stroke pattern, or fill. |
| API 27, 2 cores ~1 GHz, ~550 MB free | No heavy libraries. No chart library — they all animate. |
| `android.R.id.content` is taller than the visible area (`mFrame` 758×1024 vs `appBounds` 758×960) | Clamp to `screenHeightDp × density`, never to the parent's height. See `AppBoundsLinearLayout`. |
| **`AlarmManager` is refused for this package** (Plan.md §5.1b) | Never schedule with `AlarmManager`. Poll inside a foreground service. |
| ROM force-stops the app ~30 s after it backgrounds (Plan.md §5.1b-2) | Anything long-running needs a foreground service, and still cannot be guaranteed. |
| Android Keystore cannot generate keys (Plan.md §0) | Secrets go through `SecretVault` in `:core-data`, never a new store. |

## Non-negotiables

- **Never log or persist decrypted key material.** API tokens live in `SecretVault`
  (`:core-data`, `dev.inkdeck.data.vault`). Read them at point of use; do not cache in a field
  that outlives the call, do not put them in a URL query string, never `Log` them.
- **UI is English only.**
- **No new Gradle dependencies.** Everything you need is in `gradle/libs.versions.toml` already:
  OkHttp, Conscrypt, Room, coroutines, AndroidX. JSON is `org.json` from the framework — no
  dependency needed. If you genuinely cannot proceed without a new one, stop and report.
- **Stay inside your module.** Do not edit `settings.gradle.kts`, `app/`, `gradle/`,
  `core-eink/`, `core-data/`, `core-net/`, or another feature module. Integration is done by the
  coordinator. If you need something from a shared module that does not exist, report it rather
  than adding it.
- Repo is going **open source**. No secrets, no personal data, no hardcoded hosts in committed
  files.

## What you get

- `:core-eink` — `EinkTheme` (grey ramp, type), `EinkAnim.strip(view)`, `EinkRefresher`
  (`flush(reason)` / `notePartial(surface, reason)`), and widgets: `EinkButton`,
  `EinkIconButton`, `EinkCheckbox`, `SegmentedControl`, `EinkScrollView`, `EinkRecyclerView`,
  `PagedScrollRail`, `ListPickerDialog`, `EmptyStateView`, `StepBar`, `PressInvertView`,
  `FloatingMenu`, `AppBoundsLinearLayout`.
- `:core-data` — `SecretVault`, `HostStore`, `KnownHostsStore`, Room `InkDeckDatabase`.
- `:core-net` — `InkHttp.client` (OkHttp with Conscrypt installed) and `InkHttp.getText(url)`.

Read the source of anything you use. These were written for this panel and the comments explain
constraints you will otherwise rediscover the hard way.

## House style

Match the surrounding code. In particular: comments explain **why**, especially where the code
looks odd because the device forced it. A comment that restates the code is noise; a comment
that records a measurement or a rejected alternative is the most valuable thing in the file.

Canvas-drawn text is invisible to TalkBack and to `uiautomator` — set `contentDescription` on
every custom view that draws its own label. This has bitten us twice.

Refresh classification (design.md §13): whole-viewport changes are `[F]` — call
`refresher.flush(reason)`. Local changes are `[P]` — call `refresher.notePartial(surface, reason)`
and let the ghost budget decide. `notePartial` returns **true when it already flushed**; ignore
the result unless you have a reason not to.

## Definition of done for you

Your module compiles (`./gradlew :your-module:assembleDebug`) and is self-contained. Do **not**
try to run it on the device — the coordinator integrates and tests all phases together
afterwards. Report: what you built, what you deviated from and why, what you could not do, and
anything you found about the device or the spec that the docs should record.
