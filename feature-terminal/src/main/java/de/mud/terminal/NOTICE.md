# Vendored: `de.mud.terminal`

VT100/xterm terminal emulation, taken unmodified from ConnectBot.

| | |
|---|---|
| Source | https://github.com/connectbot/connectbot |
| Tag | `v1.9.13` |
| Path | `app/src/main/java/de/mud/terminal/` |
| Retrieved | 2026-07-25 |
| Modified | No — byte-for-byte as published |

`main` no longer contains these files; ConnectBot's later rewrite moved off them. `v1.9.13` is
the last tag that carries the classic tree.

## Licences

| File | Licence |
|---|---|
| `vt320.java` | GPL-2.0-or-later |
| `VDUBuffer.java` | GPL-2.0-or-later |
| `VDUDisplay.java` | GPL-2.0-or-later |
| `VDUInput.java` | GPL-2.0-or-later |
| `Precomposer.java` | Apache-2.0 |

The GPL files originate in "JTA — Telnet/SSH for the JAVA(tm) platform",
© Matthias L. Jugel, Marcus Meiner 1996–2005, http://javatelnet.org/.

**Plan.md §4.1 originally recorded these as BSD. They are not.** Linking them makes InkDeck a
derivative work, so distributing InkDeck would put the whole app under GPL-2.0-or-later. A
sideloaded personal build never distributes, so the obligation does not trigger — but a Play
release would (already a non-goal, Plan.md §11).

Full licence text is in each file's header. Do not strip those headers.
