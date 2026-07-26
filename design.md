# InkDeck — Design Specification (draft)

Design system and per-screen layouts for the **InkReader 6** (`EPD106A`).
Companion to [Plan.md](Plan.md).

Every measurement here derives from the device baseline in Plan.md §0 — not from a generic
phone spec.

---

## 1. Canvas

```
Physical panel          758 × 1024 px  @ 212 dpi
Density scale           1.325×  (212 / 160)
Logical screen          572 × 773 dp
Status bar (OEM)         56 px  =  42 dp     top, always present, not ours
Navigation bar          none
─────────────────────────────────────────────────────────
App content area        758 × 904 px  =  572 × 682 dp   ← measured, see Plan.md §0
Landscape content       960 × 702 px  =  725 × 530 dp   ← recheck on device in Phase 2
Panel refresh rate      16 fps  →  no animation is ever acceptable
Grayscale              ~16 levels, reflective, no frontlight
```

⚠️ **The height figure was corrected in Phase 1.** An earlier draft of this file said 968 px /
731 dp, derived from 1024 − 56. The device's own `Configuration` reports **682 dp**, because a
phantom 64 px navigation strip is excluded from `appBounds` but not from the window frame — the
full story is in Plan.md §0. Every vertical budget below has **49 dp less** to work with than
first drafted; the per-screen layouts in §6 onwards have not all been re-verified against the
smaller box yet.

**572 dp is narrower than the 600 dp tablet breakpoint.** Use phone-class single-column
layouts, not `sw600dp` tablet patterns. Qualify resources with `sw560dp` if needed.

**Density bucket:** 212 dpi sits almost exactly on `tvdpi` (213). Bitmaps will be scaled and
blurred. **Ship vector drawables only** — no PNG assets.

⚠️ **Screenshots lie.** `screencap` reads the RGB framebuffer, so colour renders as colour in
a screenshot but dithers to grey on the panel. Verified with the F-Droid icon. Validate
contrast on the device, never from `look` output.

---

## 2. Colour and contrast

A reflective 16-level panel has no colour and no backlight. The palette is a **grey ramp
only**, with tokens chosen to land on distinguishable panel levels.

### 2.1 Light theme (default)

| Token | Hex | Contrast on `paper` | Permitted use |
|---|---|---|---|
| `ink-900` | `#000000` | **21.0 : 1** | Body text, primary text, icons, borders |
| `ink-700` | `#3A3A3A` | **11.4 : 1** | Secondary text, active chart stroke |
| `ink-500` | `#6B6B6B` | **5.3 : 1** | Tertiary text, captions — **lowest legal for text** (WCAG AA) |
| `ink-300` | `#A8A8A8` | 2.4 : 1 | ❌ never text · borders, disabled outlines |
| `ink-200` | `#C8C8C8` | 1.7 : 1 | ❌ never text · dividers, grid lines |
| `paper` | `#FFFFFF` | — | Background |

**Hard rule: text is `ink-900`, `ink-700`, or `ink-500`. Nothing lighter, ever.** On a
reflective panel in poor light, `ink-300` text is invisible.

Five tokens is deliberate — a wider ramp collapses into indistinguishable greys after
dithering.

### 2.2 Dark theme (opt-in, default off)

`paper → #000000`, `ink-900 → #FFFFFF`, ramp inverted. Still 21:1.

⚠️ **Why it is off by default:** driving most pixels black increases ghosting, makes full
refreshes more visible, and — with **no frontlight on this device** — is harder to read in
dim light, not easier. Offered because it was requested; not recommended as the default.

### 2.3 Semantic states — encoded by shape, never colour

No red/green exists here. Market direction and status use **glyph + stroke**:

| Meaning | Encoding |
|---|---|
| Up / positive | `▲` + solid stroke + `+` sign |
| Down / negative | `▼` + dashed stroke + `−` sign |
| Flat / unchanged | `–` + dotted stroke |
| Error / offline | `!` in a solid black square badge |
| Stale data | `⌛` + `ink-500` text + "as of HH:mm" |

---

## 3. Typography

### 3.1 Fonts

| Role | Face | Weights | Why |
|---|---|---|---|
| UI | **Inter** | 400, 600 | Large x-height and open counters survive dithering better than Roboto |
| Mono | **JetBrains Mono** | 400, 700 | Designed for legibility at small sizes; unambiguous `l1I` `0O` |
| CJK fallback | Noto Sans SC (on device) | 400 | The stock UI is largely Chinese |

**Never ship a weight below 400.** Hairline stems disappear into the panel and ghost badly.
Two weights only — the APK stays small and the visual system stays disciplined.

### 3.2 Scale

| Token | Size | Line | px @1.325 | Use |
|---|---|---|---|---|
| `display` | 34 sp | 40 dp | 45 px | Market price, big numerals |
| `title-1` | 24 sp | 32 dp | 32 px | Screen titles |
| `title-2` | 20 sp | 28 dp | 27 px | Section headers, card titles |
| `body-l` | 18 sp | 26 dp | 24 px | Task titles, list primaries |
| `body` | **16 sp** | 24 dp | 21 px | Default body — **floor for sustained reading** |
| `caption` | 14 sp | 20 dp | 19 px | Metadata, timestamps — **absolute minimum** |
| `mono-term` | 13 sp | 18 dp | 17 px | Terminal (user-cyclable 11–17 sp) |
| `mono-ui` | 14 sp | 20 dp | 19 px | Paths, symbols, code inline |

Nothing below 14 sp. No italics (dithering destroys them). Emphasis = weight 600 or
`ink-900`, never italic or colour.

### 3.3 Terminal grid

At `mono-term` 13 sp, JetBrains Mono advance ≈ 0.6 em → 7.8 dp/col, 18 dp/row:

All four measured on the device in Phase 3, from the PTY the app actually negotiates:

| Orientation | Sidebar | Drafted | **Measured** |
|---|---|---|---|
| Landscape | hidden | 99 × 29 | **102 × 22** |
| Landscape | 220 dp split | 70 × 29 | **73 × 22** |
| Portrait | hidden | 73 × 40 | **75 × 32** |
| Portrait | drawer (overlay) | 73 × 40 | **75 × 32** — the drawer overlays, so the grid is unchanged |

⚠️ **Columns came out slightly better than drafted; rows came out much worse** — 22 instead of
29 in landscape, 32 instead of 40 in portrait. Two compounding causes:

1. The content box is **682 dp, not the 731 dp** this file originally assumed (§1, Plan.md §0).
2. The terminal screen spends **168 dp** of it on chrome: its own 56 dp header, the 56 dp key
   row, and the 56 dp tab bar.

Landscape is the worse case because the box is only 530 dp tall to begin with, leaving ~360 dp
of grid. **22 rows is tight for `vim`.** The obvious 56 dp to reclaim is the tab bar — which is
exactly what §15 open question 1 proposes (move navigation into the floating menu). That
question is now load-bearing rather than cosmetic, at least for the terminal screen.

The 220 dp split still clears the 70-column bar §3.3 sets as the practical minimum, so the
landscape posture remains the intended one for real work.

**Landscape with the split is the intended terminal posture** — 70 cols is the practical
minimum for real work. In portrait the sidebar is an *overlay drawer*, never a split: a
persistent sidebar would leave ~45 cols, which is unusable.

---

## 4. Spacing, targets, shape

```
Base grid       4 dp
Steps           4 · 8 · 12 · 16 · 24 · 32 · 48
Screen margin   16 dp
Card padding    16 dp
List row        56 dp min
Section gap     24 dp
```

| Target | Size | px |
|---|---|---|
| Android minimum | 48 dp | 64 px |
| **InkDeck minimum** | **56 dp** | **74 px** |
| Primary action | 64 dp | 85 px |
| Floating puck | 56 dp | 74 px |

**Why 56 dp not 48 dp:** there is no hover, no ripple, and ~60 ms of visual latency. Users
cannot tell a missed tap from a slow one, so they double-tap. Bigger targets fix this.

**Shape:** corner radius **4 dp** (or 0). Borders **1.5 dp** `ink-900`, dividers **1 dp**
`ink-200`. **No shadows, no elevation, no gradients** — they dither into mud.

---

## 5. Components

Notation: `[P]` = repaints partially · `[F]` = triggers a full flush.

### 5.1 Button

```
Primary                     Secondary                  Disabled
┌────────────────────┐      ┌────────────────────┐     ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┐
│████ Connect ███████│      │      Cancel        │     │      Connect        │
└────────────────────┘      └────────────────────┘     └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┘
 ink-900 fill,               1.5dp ink-900 border,      ink-300 dashed border,
 paper text, 600             ink-900 text               ink-500 text
```

Height 56 dp. **Pressed state = full invert** `[P]`, held 120 ms minimum so it is
perceptible at 16 fps. No ripple.

### 5.2 Toggle — segmented, not a switch

```
┌──────────┬──────────┐        iOS-style sliding switches are banned:
│███ ON ███│   OFF    │        the thumb travel is an animation, and at
└──────────┴──────────┘        16 fps it reads as a glitch.
```

Active segment inverts. 56 dp tall.

**As built (Phase 4): `SegmentedControl` in `:core-eink` is one View, not a row of buttons.**
It backs the `Today | Week | All | Done` filter (§8.1) and every chip row in the task editor
(§8.2) — same interaction each time: pick one of a short list, selection shown as a fill.
Three reasons it is a single custom View rather than composed buttons:

- Selection must be **fill**, not a tint. On a five-step ramp, "selected at ink_200" and
  "unselected at paper" are two pale tones that dither into the same texture.
- Only the two changed cells repaint, so moving the selection is `[P]` on a 56 dp strip.
- Cell dividers must be **shared**. Abutting bordered buttons draw two 1.5 dp strokes side by
  side, which reads as one thick smudge.

Cell labels are Canvas text, so the view carries a `contentDescription` listing the options and
the current pick — otherwise it is a single unlabelled box to TalkBack and to `uiautomator`.
The same rule applies to every Canvas-drawn label in this project.

### 5.3 Checkbox / radio

```
☐  unchecked   2dp ink-900 border, 24dp box
☑  checked     ink-900 fill, paper checkmark
◻  indeterm.   ink-900 fill, paper dash
```

**As built:** `EinkCheckbox` draws a 28 dp box inside a 56 dp hit area — the platform
`CheckBox` animates its tick, and its padding leaves the real target well under comfortable.
Indeterminate is not implemented; nothing in the app has a tri-state yet.

### 5.4 List row

```
┌─────────────────────────────────────────────────────────────────────┐
│ ☐  Rebalance VN portfolio                                    ▲ P1   │  56dp+
│    Today 14:30 · repeats weekly · ✈ telegram              caption   │
└─────────────────────────────────────────────────────────────────────┘
   ↑24dp        ↑body-l ink-900 / caption ink-500              ↑16dp
```

Selected = **left edge bar 4 dp `ink-900`** — not a background tint, which dithers.

### 5.5 Paged scrolling — replaces fling

```
                                                    ┌────┐
   content                                          │ ▲  │  ← page up
                                                    ├────┤
                                                    │3/7 │  ← position
                                                    ├────┤
                                                    │ ▼  │  ← page down
                                                    └────┘
                                                     56dp rail, right edge
```

Built at **56 dp**, not the 48 dp first drafted here: at 48 dp the buttons miss the §4 touch
minimum in one axis. 8 dp out of 572.

Fling scrolling at 16 fps is a smear. One tap = one page jump `[F]`. Drag-scroll still works
for fine adjustment but never animates momentum. This rail appears on every scrollable
surface.

### 5.6 Card

```
┌─────────────────────────────────────────┐
│ TITLE                          title-2  │   1.5dp ink-900 border
│ ─────────────────────────────────────── │   1dp ink-200 divider
│ content                                 │   16dp padding
└─────────────────────────────────────────┘   4dp radius, no shadow
```

### 5.7 States — every data surface needs all four

```
LOADING              EMPTY                 ERROR                 OFFLINE
┌───────────────┐    ┌───────────────┐     ┌───────────────┐     ┌───────────────┐
│               │    │      ╭─╮      │     │      ▪!▪      │     │      ⌛       │
│  Loading…     │    │      ╰─╯      │     │  Can't reach  │     │  No wifi      │
│  ▪▪▪▪░░░░░░   │    │  No tasks yet │     │  Binance      │     │  Showing data │
│               │    │               │     │               │     │  as of 13:05  │
│               │    │ ┌───────────┐ │     │ ┌───────────┐ │     │ ┌───────────┐ │
│               │    │ │ ██ Add ██ │ │     │ │   Retry   │ │     │ │  Retry    │ │
│               │    │ └───────────┘ │     │ └───────────┘ │     │ └───────────┘ │
└───────────────┘    └───────────────┘     └───────────────┘     └───────────────┘
```

Loading is a **stepped block bar** (5 discrete states), never a spinner — a spinner at
16 fps is a stuttering mess and burns refreshes continuously.

---

## 6. App shell

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ⌂   ←   ↻            1:35 PM              ▭  ᯤ  100% ▮        OEM  56px/42dp ║
╠═════════════════════════════════════════════════════════════════════════════╣
║                                                                             ║
║  Terminal                                              ⌕      ⋮   56dp      ║  title-1
║  ─────────────────────────────────────────────────────────────────────      ║
║                                                                             ║
║                                                                             ║
║                        content  ·  572 × 611 dp                             ║
║                                                                             ║
║                                                                    ╭───╮    ║
║                                                                    │ ✦ │ ←──╫── floating
║                                                                    ╰───╯    ║   puck 56dp
║                                                                             ║
╠═════════════════════════════════════════════════════════════════════════════╣
║    ▐▛▜▌        ☑           ▤             ✦                        56dp      ║
║   Terminal    Tasks      Market         AI                                  ║  caption
╚═════════════════════════════════════════════════════════════════════════════╝
        ↑ active tab: ink-900 icon + 600 label + 3dp top bar
          inactive:   ink-500 icon + 400 label
```

The OEM status bar (top) is **not ours** — it always shows, and its `↻` is the system's
e-ink refresh. Our own flush lives on the floating menu.

Tab switch = `[F]` full flush. Within-tab navigation = `[P]`.

### 6.1 Two containers, and which one a full-screen fragment belongs in

This Activity has exactly one tab-switching container — `R.id.content` in `activity_main.xml`,
holding the four tab fragments via `hide()`/`show()` so the terminal's SSH session survives a
trip to another tab (Plan.md §3.2). It also has the window's own root, `android.R.id.content`,
which is what `FloatingMenu` attaches to so it floats over the tab bar as well as the content.

**A screen that belongs to no single tab — Telegram settings is the first of these — must go on
`android.R.id.content`, never `R.id.content`.** The first build of Telegram settings used
`R.id.content` and broke the moment the user left it by tapping a tab instead of pressing Back:
`selectTab()` only knows about the four tab fragments, so it neither hid the settings screen nor
accounted for it, and the next tab visited gets added as a new, later — therefore higher —
sibling, silently burying the settings screen underneath while its view stayed fully attached and
clickable. It was invisible in a screenshot and still present to `uiautomator`. Using
`android.R.id.content` covers the tab bar too, so a tab tap has nothing left to reach, and it
composes correctly with the floating menu: added after it, a full-screen fragment there
correctly covers the puck for as long as it is open.

Any future full-screen settings screen (a Market settings screen, an app-wide preferences screen,
etc.) should follow the same rule: `add()` to `android.R.id.content` with `addToBackStack`, and
guard against a double-push if its entry point can be tapped again while it is already open.

---

## 7. Screen: Terminal + file sidebar

### 7.1 Portrait — sidebar is an overlay drawer

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ⌂   ←   ↻            1:35 PM                        ᯤ  100% ▮               ║
╠═════════════════════════════════════════════════════════════════════════════╣
║ ▤  t4-aws-binh                    ● connected       ⟳      ⋮                ║ 56dp
║ ─────────────────────────────────────────────────────────────────────────── ║
║ binh@ip-172-31-37-37:~$ uname -a                                            ║
║ Linux ip-172-31-37-37 6.8.0-1053-aws #56~22.04.1-Ubuntu SMP aarch64         ║
║ binh@ip-172-31-37-37:~$ ls -la                                              ║
║ total 48                                                                    ║
║ drwxr-xr-x  6 binh binh 4096 Jul 25 06:12 .                                 ║
║ drwxr-xr-x  4 root root 4096 Jul 20 11:03 ..                                ║
║ -rw-------  1 binh binh 2104 Jul 25 05:58 .bash_history                     ║
║ drwx------  2 binh binh 4096 Jul 20 11:04 .ssh                              ║
║ binh@ip-172-31-37-37:~$ █                                                   ║
║                                                                             ║
║                     73 × 40 cells  ·  mono-term 13sp                        ║
║                                                                             ║
╠═════════════════════════════════════════════════════════════════════════════╣
║ Esc │ Tab │Ctrl │ Alt │  ←  │  ↓  │  ↑  │  → │  |  │  ~  │  /  │  -  │ ⌨   ║ 56dp
╚═════════════════════════════════════════════════════════════════════════════╝
  ↑ terminal key row — the only IME on this device is Simple Keyboard,
    which cannot produce Esc/Tab/Ctrl. This row is not a convenience.
```

`▤` (top-left) opens the file drawer. `⟳` reconnects. Header dot: `●` connected ·
`○` connecting · `▪!▪` failed.

### 7.2 File drawer open (portrait) — overlays, does not squeeze

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ⌂   ←   ↻            1:35 PM                        ᯤ  100% ▮               ║
╠══════════════════════════════════════════════╗══════════════════════════════╣
║ ✕  Files                              ⋮      ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ──────────────────────────────────────────── ║░░░ terminal dimmed ░░░░░░░░░░║
║ /home/binh                                   ║░░░ (ink-200 scrim) ░░░░░░░░░░║
║ ──────────────────────────────────────────── ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ▲  ..                                        ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ▣  .ssh                                  →   ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ▣  vayla_bot                             →   ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ▢  .bash_history               2.1 KB        ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ▢  strategy.py                 14 KB         ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ▢  trades.csv                  1.2 MB        ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║ ──────────────────────────────────────────── ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║  ┌──────────┐ ┌──────────┐ ┌──────────┐      ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║  │ ⬆ Upload │ │  ✚ Dir   │ │ ▐▛▜▌ cd  │      ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
║  └──────────┘ └──────────┘ └──────────┘      ║░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░║
╚══════════════════════════════════════════════╝══════════════════════════════╝
   ← 400 dp drawer                                172 dp
```

`▣` dir · `▢` file · `▲` parent. **No thumbnails** — type glyphs only.

**Tap opens; long-press acts.** Tapping a directory descends into it, tapping a file opens the
viewer (§7.6). The rename/delete/download menu is on long-press — reading a file is what you
almost always want, and a menu standing between you and the contents of every file is friction
on every single tap.

**Navigation row**, added because the first build had none and `..` one level at a time was the
only way out:

```
║ ⌂   ↰    / home / binh / .codex                                  ║ 56dp
```

`⌂` login directory · `↰` parent · every breadcrumb segment is tappable, so four levels deep is
one tap from anywhere above. Long-press the path to type one directly. The strip scrolls
horizontally and stays pinned to its right-hand end, where the current directory is.

### 7.3 Landscape — persistent split, the intended posture

```
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║ ⌂  ←  ↻                        1:35 PM                              ᯤ  100% ▮                     ║
╠══════════════════════════════════╦════════════════════════════════════════════════════════════════╣
║ Files                     ⋮      ║ t4-aws-binh              ● connected        ⟳    ⋮             ║
║ ──────────────────────────────── ║ ────────────────────────────────────────────────────────────── ║
║ /home/binh                       ║ binh@ip-172-31-37-37:~$ tail -f /var/log/bot.log               ║
║ ──────────────────────────────── ║ 13:31:02 INFO  scan start                                      ║
║ ▲  ..                            ║ 13:31:04 INFO  4 candidates                                    ║
║ ▣  .ssh                      →   ║ 13:31:04 WARN  VN30 feed stale 42s                             ║
║ ▣  vayla_bot                 →   ║ 13:31:07 INFO  order skipped: spread                           ║
║ ▢  strategy.py       14 KB       ║ █                                                              ║
║ ▢  trades.csv       1.2 MB       ║                                                                ║
║                                  ║             70 × 29 cells                                     ║
║ ┌────────┐ ┌────────┐ ┌────────┐ ║                                                                ║
║ │⬆ Upload│ │ ✚ Dir  │ │▐▛▜▌ cd │ ║                                                                ║
║ └────────┘ └────────┘ └────────┘ ║                                                                ║
╠══════════════════════════════════╩════════════════════════════════════════════════════════════════╣
║  Esc  │  Tab  │  Ctrl │  Alt  │   ←   │   ↓   │   ↑   │   →  │   |   │   ~   │   /   │  -  │  ⌨   ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝
   ← 220 dp ─────────────────────►│◄──────────────────── 553 dp ─────────────────────────────────►
```

Rotation is app-owned via the floating menu (`setRequestedOrientation`) — verified that adb
`user_rotation` cannot rotate a portrait-locked activity. On rotate, re-negotiate the PTY and
send `SIGWINCH`.

### 7.6 File viewer

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←   .profile                                                    ⬇          ║ 56dp
║     833 B · text                                          caption ink-500   ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ # ~/.profile: executed by the command interpreter for              mono-ui  ║
║ # login shells.                                                             ║
║ # This file is not read by bash(1), if ~/.bash_profile or                   ║
║ ~/.bash_login                                             ┌────┐            ║
║ # exists.                                                 │ ▲  │            ║
║                                                           ├────┤            ║
║ if [ -n "$BASH_VERSION" ]; then                           │2/2 │            ║
║     if [ -f "$HOME/.bashrc" ]; then                       ├────┤            ║
║   . "$HOME/.bashrc"                                       │ ▼  │            ║
║     fi                                                    └────┘            ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

Full bleed, over the terminal and the sidebar both. At 220 dp a file rendered inside the
landscape sidebar would be ~28 characters wide, which is not reading — and opening over the
whole content area is what makes the §5.5 rail worth its 56 dp.

- **Read-only.** A text editor needs a cursor, a selection model and an undo stack, none of
  which are designed for this panel. `vim` over the terminal already does that job.
- **No syntax highlighting.** Colour carries no meaning here (§14 item 3), and the greys it
  would dither to cost legibility for decoration.
- **Lines wrap.** No horizontal scrolling at 572 dp.
- **Binary files get a `hexdump -C` view** rather than a screen of replacement characters —
  detected by a NUL byte or >30 % non-printables in the first 8 KB. Capped at 64 KB.
- **Text is capped at 256 KB**, and the subtitle says so in `ink-900` when it truncates. The
  viewer lays the whole buffer out in one pass; the limit is what two ~1 GHz cores can measure
  without the screen locking up, not memory.

### 7.4 Host picker / connect

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  Hosts                                                    ✚              ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ ┌─────────────────────────────────────────────────────────────────────────┐ ║
║ │ t4-aws-binh                                              ● last: 13:31  │ ║
║ │ binh@ec2-54-169-158-168.ap-southeast-1…                       caption   │ ║
║ │ key: vayla_trading_bot_key_pair.pem      ✓ host key pinned              │ ║
║ └─────────────────────────────────────────────────────────────────────────┘ ║
║ ┌─────────────────────────────────────────────────────────────────────────┐ ║
║ │ t4-aws-ubuntu                                              ○ never      │ ║
║ │ ubuntu@ec2-54-169-158-168.ap-southeast-1…                              │ ║
║ │ key: vayla_trading_bot_key_pair.pem      ⚠ host key not pinned         │ ║
║ └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                             ║
║  ┌─────────────────────────┐  ┌─────────────────────────┐                   ║
║  │  ⎘ Paste ssh_config     │  │  🗝 Import .pem          │                   ║
║  └─────────────────────────┘  └─────────────────────────┘                   ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

The `⚠ host key not pinned` badge is deliberate: it makes the security posture from
Plan.md §4.2 visible instead of silent, without blocking anything.

### 7.5 First-connect host key (TOFU)

```
┌───────────────────────────────────────────────────────────────────┐
│  New host key                                            title-2  │
│  ───────────────────────────────────────────────────────────────  │
│  t4-aws-binh has not been seen before.                            │
│                                                                   │
│  ED25519 fingerprint                                    caption   │
│  SHA256:qX7v…2mKp                                       mono-ui   │
│                                                                   │
│  Pinning it now means a later change — a possible                 │
│  interception — will be caught and reported.                      │
│                                                                   │
│  ┌────────────────────┐  ┌────────────────────┐                   │
│  │ ███ Pin & connect ██│  │      Cancel        │                   │
│  └────────────────────┘  └────────────────────┘                   │
│  Connect once without pinning                          ink-500    │
└───────────────────────────────────────────────────────────────────┘
```

---

## 8. Screen: Tasks

### 8.0 Revised: a 2×2 board, not a tab strip

The mock in §8.1 puts `Today | Week | All | Done` in a segmented control. Built and used, that
was wrong for this panel:

```
╔═════════════════════════════════════════════════════════════════════════════╗
║  7 pending · 2 overdue                                              ✚       ║ title-2, 56dp
╠══════════════════════════════════════╤══════════════════════════════════════╣
║ TODAY                             3  │ WEEK                             5   ║ 36dp, count right
╟──────────────────────────────────────┼──────────────────────────────────────╢
║ ▐☑  Rotate Binance API key           │ ☐  Rebalance VN portfolio            ║ 52dp compact rows
║     01:45                            │    Tue 28 Jul 14:30                  ║
║  ☐  Review bot logs                  │ ☐  Pay VPS invoice                   ║
║     17:00                            │    Wed 29 Jul 09:00                  ║
╠══════════════════════════════════════╪══════════════════════════════════════╣
║ ALL                              12  │ DONE                             4   ║
╟──────────────────────────────────────┼──────────────────────────────────────╢
║  ☐  Renew domain                     │ ☑  Pay VPS invoice                   ║
║     No date                          │    Yesterday 09:00 · done 08:42      ║
╚══════════════════════════════════════╧══════════════════════════════════════╝
   ↑ tap a pane header to open that pane full-screen, with the §5.5 paged rail
```

**Why.** A tab strip charges a tap *and* a full-screen `[F]` flush to answer "is there anything
else". On a 16 fps panel that is expensive enough that you stop asking, which defeats the point
of a task list. Four panes answer it without touching the screen.

**The header carries the count**, not just a title: `7 pending · 2 overdue`. Overdue is called
out separately because it is the number that changes what you do next; folded into a total it
disappears. Undated notes are excluded from *pending* — a backlog is not something hanging over
you.

**Consequences of a 286 dp pane**, all deliberate:

- **Rows are 52 dp and compact** — checkbox, one-line title, time beneath. The full-width row
  in §8.1 keeps its priority column and repeat clause; here neither fits without cutting the
  title to a dozen characters.
- **Priority survives as the title's weight**: P1 in the emphasis face, P2 and P3 plain. It is
  the one signal worth keeping and it costs no width. Colour is not available (§14 item 3) and a
  second glyph column is not affordable.
- **No paged rail per pane.** §5.5 wants a 56 dp rail on scrollable surfaces; four would eat
  224 dp of a 572 dp screen to scroll lists that are usually three rows long. The count in the
  header is what tells you there is more, and **tapping the header opens that pane full-screen**
  — where the rail, the section headers and the full-width rows all fit.
- Pane dividers are ink_900 rather than ink_200: at this density a hairline in ink_200 between
  two panes of paper reads as a smudge rather than a boundary.

### 8.1 List (full-width form — used by the expanded pane)

```
╔═════════════════════════════════════════════════════════════════════════════╗
║  Tasks                                                    ⌕      ⋮          ║ title-1
║ ─────────────────────────────────────────────────────────────────────────── ║
║ ┌────────┬────────┬────────┬────────┐                                       ║
║ │██Today█│  Week  │  All   │ Done   │   segmented, 48dp                     ║
║ └────────┴────────┴────────┴────────┘                                       ║
║                                                                             ║
║ OVERDUE                                                    caption ink-500  ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║▐│ ☐  Rotate Binance API key                             ▲ P1              │ ║
║▐│    Yesterday 18:00 · ✈                                                  │ ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ TODAY                                                                       ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ │ ☐  Rebalance VN portfolio                              ▲ P1              │║
║ │    14:30 · repeats weekly · ✈                                           │║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ │ ☐  Review bot logs on t4-aws-binh                      – P2              │║
║ │    17:00 · ✈                                                            │║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ │ ☑  Pay VPS invoice                                     – P3              │║
║ │    09:00 · done 08:42                          strikethrough, ink-500    │║
║ ─────────────────────────────────────────────────────────────────────────── ║
║                                                                   ╭───╮     ║
║                                                                   │ ✦ │     ║
║                                                                   ╰───╯     ║
╚═════════════════════════════════════════════════════════════════════════════╝
    ↑ ▐ = 4dp ink-900 left bar marks overdue
      ✈ = will notify Telegram    ▲ P1 high · – P2 normal · ▼ P3 low
```

Tapping `☐` completes in place — `[P]` on that row only, no list re-animation.

### 8.2 Task editor

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  Edit task                                          🗑        ███ Save ██ ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ TITLE                                                      caption ink-500  ║
║ ┌─────────────────────────────────────────────────────────────────────────┐ ║
║ │ Rebalance VN portfolio                                        body-l    │ ║
║ └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                             ║
║ NOTES                                                                       ║
║ ┌─────────────────────────────────────────────────────────────────────────┐ ║
║ │ Check VN30 weights, trim anything over 8%.                              │ ║
║ │                                                                         │ ║
║ └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                             ║
║ DUE                                                                         ║
║ ┌───────────────────────────┐  ┌───────────────────────────┐                ║
║ │  ▤  Sat 25 Jul 2026       │  │  ◷  14:30                 │   56dp         ║
║ └───────────────────────────┘  └───────────────────────────┘                ║
║                                                                             ║
║ REMIND                                                                      ║
║ ┌──────┬──────┬──────┬──────┬──────┐                                        ║
║ │ none │ 10m  │██1h██│  1d  │ cust │                                        ║
║ └──────┴──────┴──────┴──────┴──────┘                                        ║
║                                                                             ║
║ REPEAT                                                                      ║
║ ┌──────┬──────┬──────┬───────┬───────┐                                      ║
║ │██none│ daily│ wkdys│██wkly█│monthly│                                      ║
║ └──────┴──────┴──────┴───────┴───────┘                                      ║
║   M   T   W   T   F   S   S       ← shown only when weekly                  ║
║  ( ) ( ) ( ) ( ) ( ) (●) ( )                                                ║
║                                                                             ║
║ PRIORITY              ┌──────┬──────┬──────┐                                ║
║                       │██▲P1█│ – P2 │ ▼ P3 │                                ║
║                       └──────┴──────┴──────┘                                ║
║                                                                             ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  ✈  Notify Telegram                            ┌──────────┬──────────┐      ║
║     sends to @binh_inkdeck_bot     ink-500      │███ ON ███│   OFF    │      ║
║                                                └──────────┴──────────┘      ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

Date/time pickers are **stepped list pickers**, not the Android spinner wheels — wheels are
pure animation.

### 8.3 As built (Phase 4)

**The editor is a full-bleed overlay inside the Tasks tab**, not a second Activity and not a
back-stack Fragment — same reasoning as the file viewer in §7.6. An Activity transition is a
window animation the panel renders as a wipe, and the tab bar underneath must not move. Back
dismisses it before it dismisses the app.

**`ListPickerDialog` (`:core-eink`) is the stepped picker.** Full-screen, `backgroundDimEnabled`
false, no floating card: a dim scrim is a large grey wash that dithers, and a card's drop shadow
is exactly the gradient §2 bans. It opens scrolled to the current value rather than to the top,
carries the §5.5 paged rail, and marks the current row with the §5.3 4 dp left bar plus emphasis
weight — never a grey fill, which would be a second near-paper tone.

- **Date** — `No date`, then 180 days as `Today · Sun 26 Jul` / `Tomorrow · …` / `Wed 29 Jul`.
- **Time** — 15-minute steps, 96 rows. Minute granularity would be 1 440 rows for a precision
  no reminder here needs.
- Tapping the time field with no date set opens the **date** picker instead. A time with no date
  cannot be scheduled, and storing half a due date that silently never fires is worse than a
  redirect.

**REMIND is `none · on time · 10m · 1h · 1d`** — `cust` from the mock is replaced by `on time`;
see Plan.md §5.1a. Picking a date on a task that had none selects `on time` automatically.

**A `Device +07 | UTC` control sits under DUE**, not drawn in the §8.2 mock. Market work is
written in UTC and the trading server is not in this timezone, so a bare `14:00` on a task row
is ambiguous in a way that matters. The list appends the zone only when it differs from the
device's — see Plan.md §5.1c for the full rule, including why switching zone keeps the instant
rather than the clock face.

**The weekday row is `WeekdayPicker`, Monday-first, circles not segments.** It is the one
multi-select control in the editor and a segmented bar reads as pick-one, so the shape carries
the difference. It appears only when REPEAT is `wkly`, and that visibility change moves the form
below it by 88 dp — which is `[F]`, not `[P]`.

**Text fields have `isCursorVisible = false`.** A blinking caret is a 500 ms animation that costs
a panel refresh twice a second for as long as the field holds focus.

**No `DiffUtil` in the list adapter.** DiffUtil exists to animate insertions and moves, and the
item animator is null on every `EinkRecyclerView` anyway — a diff would compute moves that get
applied instantly regardless. What matters is which panel region is dirtied, and §13 decides
that.

**OVERDUE is a comparison against the clock, not the date.** A task due at 09:00 is overdue at
09:05; leaving it under TODAY until midnight would hide the one thing the section exists to
surface.

**One thing chasing the exit criterion caught: `AlarmManager` does not work for this app at
all** — the ROM silently discards every alarm this package registers. Reminders are delivered by
a polling ticker in a foreground service instead, which is accurate to the second while the
device is up and cannot wake it from deep sleep. Device fact, not a scheduling bug; the full
measurement is in Plan.md §5.1b.

**`✈ Notify Telegram` defaults ON, not OFF as the phone-shaped framing in §8.2 implies.** Once
Telegram shipped (Phase 5) it became the primary reminder channel and the local notification the
fallback (Plan.md §5.1d) — the tablet cannot wake itself to show a local notification, so leaving
this off is the choice that needs justifying, not the other way round. Confirmed end to end on
device: a task due "now" with this on tried the Telegram route first, found no chat paired yet,
and fell through to the local notification correctly — the fallback chain works whether or not
Telegram is actually configured.

---

## 9. Screen: Market

### 9.1 Dashboard — togglable widget grid

```
╔═════════════════════════════════════════════════════════════════════════════╗
║  Market                                     ⟳ 13:35    ⊞ widgets   ⋮        ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ ┌────────────────────────────────┐ ┌────────────────────────────────┐       ║
║ │ BTC/USDT              Binance  │ │ ETH/USDT              Binance  │       ║
║ │ 108,412.50          display    │ │ 3,914.20                       │       ║
║ │ ▲ +2.14%  +2,271                │ │ ▼ −0.83%  −32.80               │       ║
║ │      ╱╲    ╱╲╱                  │ │ ╲    ╱╲                        │       ║
║ │  ╱╲╱   ╲╱╱      solid=up        │ │  ╲╱╲╱  ╲╲   dashed=down        │       ║
║ │ 24h            sparkline 64dp   │ │ 24h                            │       ║
║ └────────────────────────────────┘ └────────────────────────────────┘       ║
║ ┌────────────────────────────────┐ ┌────────────────────────────────┐       ║
║ │ VN30                  ⚠ unoff. │ │ AAPL                  Finnhub  │       ║
║ │ 1,342.18                       │ │ 241.85                         │       ║
║ │ ▲ +0.62%  +8.24                 │ │ ▲ +1.05%  +2.51                │       ║
║ │   ╱╲╱╲  ╱                       │ │    ╱╲  ╱╲╱                     │       ║
║ │  ╱    ╲╱                        │ │ ╱╲╱  ╲╱                        │       ║
║ │ ⌛ as of 13:05      ink-500      │ │ 1d                             │       ║
║ └────────────────────────────────┘ └────────────────────────────────┘       ║
║ ┌────────────────────────────────┐                                          ║
║ │ FPT                   ⚠ unoff. │                              ╭───╮       ║
║ │ 138.40                         │                              │ ✦ │       ║
║ │ – 0.00%   0.00                  │                              ╰───╯       ║
║ └────────────────────────────────┘                                          ║
╚═════════════════════════════════════════════════════════════════════════════╝
   2 columns × 270 dp cards
```

`⚠ unoff.` is a permanent, honest label on every VN widget — those endpoints are
undocumented (Plan.md §5.2). `⌛ as of` shows whenever data is older than 2× the refresh
interval.

### 9.2 Widget picker

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  Widgets                                                  ✚ symbol        ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ CRYPTO                            Binance · no key · documented   ink-500   ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ │ BTC/USDT                                          ┌────┬────┐            │║
║ │                                                   │█ON█│ off│            │║
║ │ ETH/USDT                                          │█ON█│ off│            │║
║ │ SOL/USDT                                          │ on │█OFF│            │║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ US STOCKS                              Finnhub · free key         ink-500   ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ │ AAPL                                              │█ON█│ off│            │║
║ │ NVDA                                              │ on │█OFF│            │║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ VN STOCKS                    ⚠ unofficial endpoint — may break    ink-500   ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ │ VN30                                              │█ON█│ off│            │║
║ │ FPT                                               │█ON█│ off│            │║
║ │ VCB                                               │ on │█OFF│            │║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ REFRESH                                                                     ║
║ ┌────────┬────────┬────────┬────────┐                                       ║
║ │ manual │██5 min█│ 15 min │ 30 min │                                       ║
║ └────────┴────────┴────────┴────────┘                                       ║
║  Auto-refresh pauses when the screen is off.            caption ink-500     ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

### 9.3 Symbol detail

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  BTC/USDT                                    Binance      ⟳ 13:35        ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  108,412.50                                                    display      ║
║  ▲ +2,271.30  +2.14%   24h                                     body-l       ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ ┌───────┬───────┬───────┬───────┬───────┬───────┐                           ║
║ │  1H   │  4H   │██ 1D ██│  1W  │  1M   │  1Y   │      48dp                 ║
║ └───────┴───────┴───────┴───────┴───────┴───────┘                           ║
║                                                                             ║
║ 110k ┤····································································· ║
║      │                                        ▐  ▐▌                         ║
║ 109k ┤·····························▐▌·▐··▐▌··▐▌··▐▌·························║
║      │                        ▐▌  ▐▌ ▐▌ ▐▌  ▐▌   ▐                          ║
║ 108k ┤················▐▌··▐▌··▐▌··▐▌·▐▌·▐▌··▐▌·····························║
║      │      ▐▌  ▐▌   ▐▌  ▐▌  ▐▌   ▐  ▐                                      ║
║ 107k ┤·▐▌··▐▌··▐▌···▐▌··▐▌···▐····································· ······║
║      │ ▐   ▐   ▐    ▐   ▐                                                   ║
║ 106k ┤····································································· ║
║      └──┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬────         ║
║        00    03    06    09    12    15    18    21    00    03            ║
║      hollow ▯ = up candle · filled ▮ = down · 1dp ink-200 grid              ║
║                                                                             ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  Open    107,980.10        High    109,204.00                               ║
║  Low     106,712.40        Volume  42,180 BTC                    mono-ui    ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  Data: Binance public API                                caption ink-500    ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

Candles are **hollow vs filled**, never red/green. Grid `ink-200` 1 dp, axis labels
`caption`. Timeframe change = `[F]`.

### 9.4 As built (Phase 7)

**Rendering matches the mock as drawn** — Canvas sparklines and candlesticks, no chart library,
series told apart by stroke pattern rather than colour. What differs is underneath it, and it is
covered in Plan.md §5.2: **Stooq, the no-key US fallback, now answers a JavaScript proof-of-work
challenge instead of CSV**, found live during this test pass, not in the code. The `MarketProvider`
abstraction did exactly what it was built for — the AAPL card reads "Stooq changed its CSV"
instead of garbage or a crash — but it means a US symbol needs a Finnhub key today; there is no
working no-key US quote source left.

**Live-verified on the device, 2026-07-26:** Binance (BTC/USDT, ETH/USDT) and the VN adapters
(VN30 via VNDirect/TCBS) both return live data, the `⚠ unoff.` marker renders on the VN card
exactly as drawn above, auto-refresh was observed changing a price between two screenshots
minutes apart, and the symbol-detail candle chart with timeframe switching was exercised on
BTC/USDT. The widget picker's on/off toggles and grouped attribution captions (§9.2) match the
mock.

---

## 10. Screen: AI chat (BYOK)

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  AI chat                              claude-opus-5 ▾        ⋮            ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║                                                                             ║
║                        ┌──────────────────────────────────────────────────┐ ║
║                        │ Why is my bot skipping orders on VN30?           │ ║
║                        │                                    13:31 caption │ ║
║                        └──────────────────────────────────────────────────┘ ║
║                          ↑ user: 1.5dp border, right-aligned, max 80%       ║
║                                                                             ║
║ ┌──────────────────────────────────────────────────┐                        ║
║ │ The log line `order skipped: spread` means your   │                       ║
║ │ spread guard rejected the fill. Check the         │                       ║
║ │ threshold in strategy.py:                          │                      ║
║ │ ┌──────────────────────────────────────────────┐ │                        ║
║ │ │ MAX_SPREAD_BPS = 15          mono-ui         │ │  ← code: ink-200 fill  ║
║ │ └──────────────────────────────────────────────┘ │                        ║
║ │ VN30 mid-session spreads often exceed that.       │                       ║
║ │                                      13:31 caption│                       ║
║ └──────────────────────────────────────────────────┘                        ║
║   ↑ assistant: no border, left-aligned, ink-900                             ║
║                                                                             ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ ┌──────────────────────────────────────────────────────────┐ ┌────────────┐ ║
║ │ Ask something…                                  ink-500  │ │ ███ ↑ ████ │ ║
║ └──────────────────────────────────────────────────────────┘ └────────────┘ ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

**Streaming rule:** buffer tokens and repaint at most **twice per second**, on sentence or
newline boundaries. Token-by-token rendering would demand ~30 partial refreshes/second on a
16 fps panel — unreadable and a battery sink. While buffering, show a stepped `▪▪▪░░` bar in
the assistant bubble.

### 10.1 BYOK profile settings

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  AI providers                                              ✚             ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║ ┌─────────────────────────────────────────────────────────────────────────┐ ║
║ │ ● anthropic-main                                          active        │ ║
║ │   https://api.anthropic.com                              mono-ui       │ ║
║ │   claude-opus-5                                                        │ ║
║ │   key  sk-ant-…7f3a                              ink-500  🗝 vault      │ ║
║ └─────────────────────────────────────────────────────────────────────────┘ ║
║ ┌─────────────────────────────────────────────────────────────────────────┐ ║
║ │ ○ local-llama                                                          │ ║
║ │   http://192.168.1.20:11434/v1                                         │ ║
║ │   llama3.1:8b                                                          │ ║
║ │   key  (none)                                                          │ ║
║ └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                             ║
║  Keys are stored encrypted in the app vault, never on /sdcard.   ink-500    ║
║  Profiles can also be set from Telegram with /llm — see below.              ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

### 10.2 As built (Phase 8)

**The chunking rule is implemented as `ChunkBuffer`, and it is the module.** Four rules in strict
priority order: never faster than 500 ms; prefer a sentence or newline boundary; force an
emission at 320 characters (code blocks and tables contain no sentence terminator at all, so
without this a 40-line block would arrive in one flush at the end); force one after 2 s (a slow
endpoint dribbling tokens hits neither of the first two). It is pure logic with the clock passed
in — no Handler, no coroutine — because the behaviour it encodes is invisible to `screencap`
(Plan.md §3.4) and the only other way to check it is to watch the panel.

**A streamed chunk is `[P]` and a new message is `[F]`, and they are told apart by message
*count*, not content.** The last bubble's text changes on every chunk, so any content-based diff
classifies every chunk as a new transcript and flushes the whole panel twice a second — which is
the exact failure the buffer exists to prevent.

**`▾` on the model name is a `ListPickerDialog`, and history shares it.** §10 draws a dropdown;
a menu anchored under a chip is a popup window with its own shadow (§4) and its own animation.
The full-screen picker from §8.3 is already the sanctioned "pick one of a short list" and it
carries the §5.5 rail for free. History reuses it with `＋ New chat` as row 0 rather than owning
a screen — ten conversations do not earn 56 dp of chrome and a navigation level.

**The masked key is computed once, at the moment the key is stored, and persisted with the
profile.** So drawing §10.1 never opens the vault. There is no reveal affordance: the only use
for one is also the fastest way to read a key off a device in a café.

**Markdown is fenced code blocks and nothing else.** Bold and italic have nowhere to go (§14 item
5 bans italics, emphasis is weight), headings and lists read fine as the literal `##` and `-` the
model wrote, and links have nothing to open. An unterminated fence renders as code, which is the
normal case mid-stream.

**The screen draws its own 56 dp bar** (active model · history · providers), so the `ai` tab
should get `hidesTitleBar = true` in `MainActivity` the way the terminal tab does. Otherwise the
transcript spends 112 dp of a 682 dp column on chrome, 56 of it on a bar reading "AI".

**History is capped, and the cap is a total, not a count.** `SharedPreferences` holds its whole
file in memory for the process lifetime and an LLM answer is the longest text this app ever
stores, so: 4 000 characters per message, 40 messages per conversation, 10 conversations, and —
the limit that actually binds — 200 KB across the lot, oldest conversations dropped first.

**One thing is not implemented:** a system prompt per profile. Nothing in §10 or Plan.md §7 asks
for one, and it is a text field plus a request-shape change in both providers whenever it is
wanted.

**One thing this screen flagged and integration then fixed:** a shared `SecretVault` instance.
The vault keeps its unlocked data key per instance, and this module — like `:feature-terminal`,
`:feature-telegram` and `:feature-market` — had its own `SecretVault(context)` construction,
which meant `Protection.PASSPHRASE` unlocking in one tab would leave every other tab still
locked. Fixed at integration with `SecretVault.get(context)`, a process-wide singleton in
`:core-data` (Plan.md §4.3); every module now goes through it, and this screen's own unlock
prompt only ever fires once per app session, not once per tab.

---

## 11. Floating action menu

### 11.1 States

```
COLLAPSED (idle)        RESTING (edge-snapped)       EXPANDED
                                                     ┌──────┬──────┬──────┐
   ╭───╮                            ┆ ╭─╮ ┆          │  ↻   │  ⌨   │  ✦   │
   │ ✦ │  56dp                      ┆ │✦│ ┆ 32dp     │Flush │ Keys │  AI  │
   ╰───╯  1.5dp ring                ┆ ╰─╯ ┆ drawn    ├──────┼──────┼──────┤
   ink-900 glyph                    └─────┘ 56dp hit  │  ◐   │  ⟳   │  ✚   │
   paper fill                                         │Theme │Rotate│Quick │
                                                      ├──────┼──────┼──────┤
                                                      │  ◷   │  ▤   │  ⋯   │
                                                      │Awake │Files │ More │
                                                      └──────┴──────┴──────┘
                                                       3×3, 72dp cells, 216dp
```

### 11.2 Behaviour

| Gesture | Action |
|---|---|
| Drag | Move; snaps to nearest edge on release; position persisted |
| Tap | Expand / collapse — **one repaint, no fan-out animation** |
| Long-press | **Immediate full refresh** (skips the menu — the most frequent action) |
| Tap outside | Collapse |
| Idle 10 s | Shrink the **drawn** puck to 32 dp, hugging the edge. The 56 dp hit area does not move — see below |

The grid opens **away from the nearest edge** so it never clips — a true radial menu loses
items in corners on a 572 dp screen.

**Corrected after use: the resting tab does not hang half off the edge.** This section
originally specified a 32 dp tab positioned half outside the screen. Built that way it was
unusable — at 758 px wide the view sat at x 737..779, the parent clipped everything past 758,
and what was left to aim at was a 21 px sliver. Sixteen dp, against a 56 dp minimum (§4) that
exists precisely because ~60 ms panel latency makes a missed tap feel identical to a slow one.
The shrink is now **paint-only**: the view keeps its 56 dp bounds and stays fully on screen,
only the drawn puck shrinks and hugs the outer edge. Visual weight drops as intended; the target
does not move.

**The puck is also clamped to the visible area, not the parent.** `android.R.id.content` is
taller than the screen on this device (Plan.md §0: `mFrame` 758×1024 against `appBounds`
758×960), so a drag to the bottom could put the puck in the gap below the visible region with no
way to retrieve it. Restored positions are coerced too, so a fraction saved by an older build —
or under a different window size — cannot strand it out of reach.

**Every cell is icon + label.** Icon-only menus are unreadable once dithered, and there is no
hover tooltip to fall back on.

### 11.3 Item map

| Cell | Action | Refresh |
|---|---|---|
| `↻ Flush` | Force full-screen waveform flush (`android.eink.force.refresh` broadcast — verified, Plan.md §3.4) | `[F]` |
| `⌨ Keys` | Toggle on-screen keyboard / terminal key row | `[P]` |
| `✦ AI` | Open AI chat | `[F]` |
| `◐ Theme` | Light ⇄ dark | `[F]` |
| `⟳ Rotate` | `setRequestedOrientation` P→L→revP→revL; re-negotiates PTY | `[F]` |
| `✚ Quick` | New task, from anywhere | `[F]` |
| `◷ Awake` | Hold screen on | — |
| `▤ Files` | Toggle file sidebar | `[P]` |
| `⋯ More` | Snippets · clipboard · font size · screenshot · sync now | `[P]` |

Confirmed items are `Keys`, `AI`, `Theme`, `Rotate`; the rest are the §6.2 proposals pending
your pick. `Flush` earns cell 1 because it is the one control this hardware genuinely needs.

**Built in Phase 6.** All nine cells exist; `More` is drawn disabled rather than hidden, so the
grid keeps a stable shape and cell position stays learnable. `Quick` was enabled in Phase 4.
Three implementation notes worth keeping:

- **`Quick` switches to the Tasks tab and opens the editor** rather than floating a capture
  sheet over whatever is on screen. A sheet would be a second editor to keep in step with §8.2,
  and on a 682 dp column it covers what it is drawn over anyway. It is therefore `[F]`, not
  `[P]` as originally drafted.

- The grid is positioned with **translation, not layout margins**. It is measured and shown
  inside one call, and margins only take effect on the next layout pass — the first attempt put
  the grid at 0,0 every time regardless of where the puck was.
- `Theme` **recreates the Activity**, which destroys the Fragment holding the SSH session. It
  now asks first when a session is live. The real fix is to hoist `SshSession` into a ViewModel
  so it survives recreation; until then the prompt is the honest behaviour.

### 11.4 On-screen keyboard (expanded)

```
╔═════════════════════════════════════════════════════════════════════════════╗
║                              terminal output                                ║
╠═════════════════════════════════════════════════════════════════════════════╣
║ Esc │ Tab │ Ctrl│ Alt │  ←  │  ↓  │  ↑  │  →  │  |  │  ~  │  /  │ - │ ⌨ ▾  ║ 56dp
╠═════════════════════════════════════════════════════════════════════════════╣
║  q │ w │ e │ r │ t │ y │ u │ i │ o │ p                                      ║
║   a │ s │ d │ f │ g │ h │ j │ k │ l                              48dp keys  ║
║  ⇧ │ z │ x │ c │ v │ b │ n │ m │ ⌫                                          ║
║  ?123 │ , │      space      │ . │  ⏎                                        ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

Key press = **invert for 120 ms** `[P]` — no popup preview, no key-pop animation.

Phase 1 ships the top row only (Esc/Tab/Ctrl/Alt/arrows/symbols), which is what the terminal
cannot live without. Rationale in Plan.md §6.1.

⚠️ **The Latin block below is now weakly justified.** This section was drafted when the only IME
was the Chinese `com.sohu.inputmethod.sogou.oem`. That is no longer installed; the device now
has **Simple Keyboard** (`rkr.simplekeyboard.inputmethod`), a perfectly usable Latin IME. The
control-key row above is still essential — no IME produces Esc/Tab/Ctrl/Alt — but building a
second Latin keyboard now duplicates something that works. Open decision.

---

## 12. Screen: Telegram settings

```
╔═════════════════════════════════════════════════════════════════════════════╗
║ ←  Telegram                                                                 ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  ● Connected · @binh_inkdeck_bot                                            ║
║    listening — long-poll while the app is running          caption ink-500  ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  PAIRED CHAT                                                                ║
║  binh  ·  id 84021…                                       ✓ allowlisted     ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  ┌───────────────────────────────────────────────────────────────────────┐  ║
║  │ ▪!▪  Keys sent over Telegram are stored in that chat's history on     │  ║
║  │      Telegram's servers. InkDeck deletes the message the moment it    │  ║
║  │      is read, but use keys you can rotate — and push .pem files       │  ║
║  │      over USB instead.                                                │  ║
║  │                                                        body ink-900   │  ║
║  └───────────────────────────────────────────────────────────────────────┘  ║
║        ↑ 1.5dp ink-900 border — a real warning, not a tinted "info" box     ║
║                                                                             ║
║  ┌──────────────────────────────┬──────────────────────────────┐            ║
║  │ ███ Auto-delete secrets ████ │            OFF               │            ║
║  └──────────────────────────────┴──────────────────────────────┘            ║
║   Strongly recommended. Deletes /llm and /key messages on ingest.  ink-500  ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  COMMANDS                                                                   ║
║  /llm <provider> <base_url> <model> <key>                        mono-ui    ║
║  /task add <title> | <due> | <repeat>                                       ║
║  /task list · /task done <id> · /task del <id>                              ║
║  /note <text>       /host add       /key <file>                             ║
║  /watch add <market> <symbol>       /status      /refresh                   ║
║ ─────────────────────────────────────────────────────────────────────────── ║
║  ┌─────────────────────────┐  ┌─────────────────────────┐                   ║
║  │  ⎘ Re-pair              │  │  ✕ Disconnect           │                   ║
║  └─────────────────────────┘  └─────────────────────────┘                   ║
╚═════════════════════════════════════════════════════════════════════════════╝
```

Warnings are bordered, never colour-tinted — a `#FFF3CD` amber wash becomes an
indistinguishable pale grey after dithering.

### 12.1 As built (Phase 5)

**The status caption above was rewritten, not just filled in.** The original mock's "polling
every 5 min (screen off, charging)" describes an adaptive schedule Plan.md §7.3 later struck as
unimplementable — this package is granted no timer to wake on, so there is no "screen off,
charging" state to report. What actually shows is one of six phases: connected-and-listening,
waiting to pair, no bot token, vault locked, retrying-with-a-countdown, or stopped. Each is a
plain sentence, not a fabricated interval.

**The command reference lists what Phase 5 actually built**, not the §7.2 sketch — see the
"as built vs. sketched" split there. `/host`, `/watch`, `/refresh`, `/llm list`/`use` are absent
because they reach into modules this screen's module does not depend on; an unpaired chat gets
no reply at all rather than a rejection, so a stranger probing the bot learns nothing.

**Auto-delete's failure contract is part of the screen, not just the toggle.** A failed delete
is reported in the chat reply itself, in capitals, with instructions to rotate the key — the
in-app warning box above is the *policy*, the chat reply is what tells you the policy didn't
hold for one specific message.

**Verified live, 2026-07-26:** enabling Telegram control starts the foreground service and the
long-poll runs against `api.telegram.org` for 2+ minutes with no TLS error, confirming Conscrypt
negotiates modern TLS from this API-27 device (Plan.md §2.4). Completing `/pair` itself needs a
human on the other end with a Telegram client, which this test pass did not have — so pairing,
and everything gated behind it, is built and unverified rather than untested.

---

## 13. Refresh classification (implementation checklist)

| Interaction | Mode | Note |
|---|---|---|
| Terminal character output | `[P]` | Dirty cell rects only; throttled to ≤ 8 Hz |
| Terminal screen clear / `vim` redraw | `[F]` | Full-surface change anyway |
| Key row press | `[P]` | Single key rect invert |
| List row check / uncheck | `[P]` | That row only — but see the note below the table |
| Page up / down | `[F]` | Whole viewport replaced |
| Tab switch | `[F]` | — |
| Drawer open / close | `[F]` | Scrim covers the screen |
| Floating puck drag | `[P]` | Puck rect + vacated rect |
| Menu expand / collapse | `[P]` | Menu bounds |
| Theme switch | `[F]` | Every pixel inverts |
| Rotation | `[F]` | — |
| Market auto-refresh | `[P]` | Only cards whose values changed |
| Chart timeframe change | `[F]` | — |
| AI streaming chunk | `[P]` | ≤ 2 Hz, sentence boundaries |
| Task editor open / close | `[F]` | Full-bleed overlay replaces the viewport |
| Task filter change | `[F]` | Whole list replaced |
| Repeat set to / from `wkly` | `[F]` | Weekday row appears; form below shifts 88 dp |
| List picker open / dismiss | `[F]` | Full-screen dialog either way |
| **Ghost budget exceeded (8 `[P]`)** | `[F]` | Automatic; tune on device with `einknav watch` |

**Ticking a task box is only locally `[P]` in one of the two cases.** A completed one-off leaves
the current filter, so the list closes up beneath it and the dirty region is everything from
that row down; a repeating task rolls forward and stays, but its subtitle changes length. Rather
than guess per case, `TasksFragment` notes a partial and lets the ghost budget decide — which is
what the budget is for.

---

## 14. Global don'ts

1. No animation of any kind — no transitions, ripples, fades, slides, spinners, overscroll glow.
2. No fling / momentum scrolling. Paged rail instead (§5.5).
3. No colour as the sole carrier of meaning. Shape and stroke pattern carry it.
4. No shadows, elevation, or gradients.
5. No text below 14 sp; no font weight below 400; no italics.
6. No `ink-300`/`ink-200` text — borders and dividers only.
7. No bitmap assets — vectors only (212 dpi falls between buckets).
8. No thumbnails or image previews in file lists.
9. No indeterminate progress. Stepped bars only.
10. No hover-dependent affordances. There is no cursor.
11. Never validate contrast from a `screencap` PNG — it shows RGB the panel cannot display.

---

## 15. Open design questions

1. **Tab bar vs. left rail** — at 572 dp a bottom tab bar wins, but the floating puck can
   overlap it. Alternative: put navigation entirely in the floating menu and reclaim 56 dp.
2. **Terminal font size default** — 13 sp gives 70 cols in landscape; 11 sp gives 83 but is
   near the legibility floor on a reflective panel. Needs a device trial.
3. **Portrait terminal** — worth supporting properly, or should the terminal force landscape?
4. **Market card density** — 2 columns × 270 dp shows 4–5 widgets; a compact 1-line list
   variant would show ~10. Both, user-toggled?
5. **Ghost budget N** — 8 is a starting guess. Must be tuned on hardware.
6. **Chart type** — candles are dense at 572 dp; a line + volume bars may read better.
