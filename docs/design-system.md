# Design System (Radhika Farms)

IMPLEMENTED in redesign phase 1 (2026-10-15). The look adapts a vintage farm-label style to a dairy:
flat colour bands, cocoa text on milk cream, butter-yellow pill buttons, and a warm serif for headings.
The interactive mockup the owner approved is the reference for later phases.

## Where it lives

- `frontend/src/index.css`: colour tokens, fonts, base element styles, focus and reduced-motion rules.
- `frontend/src/App.css`: every component style, built only from the tokens.
- No CSS framework is used. `AGENTS.md` mentions Tailwind, but it was never installed; plain CSS with tokens
  was kept to avoid rewriting every screen for no user-visible gain.

## Colour tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `--canvas` | `#faf6ea` | `#1f1612` | Page background |
| `--surface` | `#fffdf6` | `#2b1f1a` | Panels, inputs |
| `--ink` | `#3b2722` | `#f3ead3` | Text, borders, dark fills |
| `--ink-soft` | `#6b5448` | `#c9b8a4` | Secondary text |
| `--line` | `#e4d9bf` | `#4a3830` | Hairlines |
| `--butter` / `--on-butter` | `#f5c64f` / `#3b2722` | same / `#2a1c17` | Primary buttons, active nav |
| `--band` / `--on-band` | `#3b2722` / `#f6efd9` | same | Header and footer bands |
| `--sky` | `#9cc8d6` | `#7fb3c4` | Marquee strip only |
| `--grass` / `--grass-tint` | `#3f6b3a` / `#e3eedb` | `#9cc98f` / `#2b3a26` | Collected, issued, success |
| `--red` / `--red-tint` | `#a0342a` / `#f6e1dc` | `#ec9486` / `#3d2420` | Errors, skipped, amounts owed |
| `--butter-tint` | `#fbefc6` | `#3a2f17` | Pending, draft, open |
| `--paper` | `#fffbee` | `#2f231d` | Receipt-style bills |

Dark mode follows the device setting (`prefers-color-scheme`). There is no manual toggle yet.

Contrast was checked for text pairs: cream on cocoa 11.7:1, cocoa on butter 9.2:1, cocoa on sky 5.5:1,
red on cream 5.8:1. Barn red on cocoa (2.0:1) and cream on sky (2.1:1) fail, so they are never used for text.

## Type

- **Fraunces** (800–900, soft): headings and big numbers.
- **Atkinson Hyperlegible**: all body and interface text. It was designed for low-vision readers and
  clearly separates characters such as 0/O and 1/l, which suits older customers.
- **IBM Plex Mono**: labels, eyebrows, and receipt-style bills.
- Body text is 17px with 1.5 line spacing; hints are at least 14px; buttons and inputs are at least 40–48px
  tall for easy tapping.

## Rules

- Flat: no shadows. Hierarchy comes from colour bands, borders, and type size.
- Every main action is a fully rounded pill. Cards and sections have square corners.
- Large display type and the scrolling marquee appear only on the public sign-in page, never on working
  screens. The marquee stops when the device asks for reduced motion.
- The old time-of-day themes (morning, afternoon, evening, night) were removed on the owner's request,
  together with `TimeTheme.ts` and `MilkCanMascot.tsx`.
- The workspace sidebar is restyled as a cocoa header band with CSS only; component markup is unchanged.
- User-facing text says "Radhika Farms". "Milk Web" remains the project and repository name.
