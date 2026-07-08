---
name: androidskills.dev
description: A searchable index of AI coding skills for Android and Kotlin development.
colors:
  signal-green: "#34d27e"
  signal-green-strong: "#1f9d5b"
  signal-green-soft: "#e7f8ee"
  signal-green-text: "#0b7a43"
  signal-green-ink: "#04130a"
  accent-green: "#34d27e"
  accent-indigo: "#6c8cff"
  accent-orange: "#ff7a45"
  accent-mono: "#8b909a"
  info: "#2d69ce"
  info-soft: "#e8f0fd"
  warn: "#b5820a"
  warn-soft: "#fbf2da"
  danger: "#b83030"
  danger-soft: "#fbe6e6"
  neutral-bg: "#fbfbfc"
  neutral-bg-dark: "#0e1014"
  neutral-surface: "#ffffff"
  neutral-surface-dark: "#16191f"
  neutral-surface-2: "#f5f6f8"
  neutral-surface-2-dark: "#1b1f26"
  neutral-inset: "#eef0f3"
  neutral-inset-dark: "#0a0c10"
  neutral-border: "#e7e8ec"
  neutral-border-dark: "#262b33"
  neutral-border-2: "#d8dade"
  neutral-border-2-dark: "#343a44"
  neutral-text: "#15171c"
  neutral-text-dark: "#f1f3f6"
  neutral-text-dim: "#565c67"
  neutral-text-dim-dark: "#a6adb8"
  neutral-text-faint: "#6b7280"
  neutral-text-faint-dark: "#868d99"
typography:
  display:
    fontFamily: "Bricolage Grotesque, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "clamp(36px, 5vw, 56px)"
    fontWeight: 800
    lineHeight: 1.05
    letterSpacing: "-0.03em"
  headline:
    fontFamily: "Bricolage Grotesque, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "26px"
    fontWeight: 700
    lineHeight: 1.12
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Bricolage Grotesque, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "16.5px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  body:
    fontFamily: "Bricolage Grotesque, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: "normal"
  lede:
    fontFamily: "Bricolage Grotesque, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "18px"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "normal"
  label:
    fontFamily: "Spline Sans Mono, ui-monospace, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "11px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "0.08em"
  mono:
    fontFamily: "Spline Sans Mono, ui-monospace, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.7
    letterSpacing: "normal"
rounded:
  xs: "5px"
  sm: "7px"
  md: "11px"
  lg: "16px"
  xl: "22px"
  pill: "999px"
  tag: "6px"
  icon: "8px"
  swatch: "9px"
  skeleton: "10px"
  phone: "42px"
spacing:
  gap: "16px"
  row-pad: "12px"
  card-pad: "22px"
  section-y: "64px"
  control-h: "40px"
components:
  button-primary:
    backgroundColor: "{colors.signal-green}"
    textColor: "{colors.signal-green-ink}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "{spacing.control-h}"
  button-primary-hover:
    backgroundColor: "{colors.signal-green-strong}"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.neutral-text-dim}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "{spacing.control-h}"
  button-outline:
    backgroundColor: "transparent"
    textColor: "{colors.neutral-text}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "{spacing.control-h}"
  card:
    backgroundColor: "{colors.neutral-surface}"
    rounded: "{rounded.lg}"
    padding: "{spacing.card-pad}"
  input:
    backgroundColor: "{colors.neutral-surface}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "{spacing.control-h}"
  chip:
    backgroundColor: "{colors.neutral-surface-2}"
    textColor: "{colors.neutral-text-dim}"
    rounded: "{rounded.pill}"
    padding: "0 11px"
    height: "28px"
  tag:
    backgroundColor: "{colors.neutral-surface-2}"
    textColor: "{colors.neutral-text-dim}"
    rounded: "6px"
    padding: "0 8px"
    height: "22px"
---

# Design System: androidskills.dev

## 1. Overview

**Creative North Star: "The Workbench"**

The interface is a clean, well-organized workbench: every tool has a place, nothing is decorative, and every element earns its keep by moving the user toward finding, evaluating, or managing a skill. The aesthetic is straightforward, functional, and getting-shit-done — a tool made by developers for developers, not a billboard or a corporate brochure.

The system is dual-themed (light and dark) via CSS `light-dark()`, with dark mode as the primary, most-tested environment. Surfaces are layered with subtle borders and tonal shifts rather than heavy shadows. Motion is restrained and purposeful: state changes are quick, entrances are subtle, and everything respects `prefers-reduced-motion`.

The design explicitly rejects official Android/Google branding, soul-less corporate Apple minimalism, cute or whimsical UI, and SaaS marketing-page theater. No big hero metrics, no gradient-text slogans, no ornamental flourishes.

**Key Characteristics:**
- Dark-first, light-capable via `light-dark()` tokens.
- One accent: a functional signal green used for verification, primary actions, and status.
- Monospace reserved for machine values, slugs, labels, and code.
- Cards with 16px corners, 1px borders, and hover lift on search results.
- Sticky headers with frosted-glass blur, not solid blocks.
- Radius vocabulary: core tokens are 7/11/16/22px, with smaller 5/6/8/9/10px values for tiny elements and 42px reserved for the phone mockup.

## 2. Colors

The palette is built around a single functional accent — signal green — against a cool, neutral two-tone system. Color is used sparingly and semantically; most of the interface is neutral surface, ink, and border, with green reserved for action, verification, and success.

The accent color is configurable (green, indigo, orange, mono) via a `data-accent` attribute on `:root`. Each variant is exposed as its own token (`--accent-green`, `--accent-indigo`, `--accent-orange`, `--accent-mono`) so swatches and previews can reference the inactive variants without depending on the currently active `--accent` value.

### Primary
- **Signal Green** (`#34d27e`): The primary accent. Used for primary buttons, verified badges, the active state of selected filters, focus-ring cores, and the install/action signal. It is intentionally close to Android green but tuned slightly cooler and more saturated to read as independent, not official.
- **Signal Green Strong** (`#1f9d5b` light / `#5be39a` dark): Hover and emphasized states of the accent.
- **Signal Green Soft** (`#e7f8ee` light / `#112318` dark): Subtle tint backgrounds for active filters, selected items, and soft callouts.
- **Signal Green Text** (`#0b7a43` light / `#46e08f` dark): Text and icon color on neutral surfaces where the full signal green would be too loud.
- **Signal Green Ink** (`#04130a`): The darkest green-black, used only for text on top of the signal-green button.
- **Green Accent** (`#34d27e`): Alias for the green variant token, used for accent swatches and previews.
- **Indigo Accent** (`#6c8cff`): Indigo variant token for swatches and previews.
- **Orange Accent** (`#ff7a45`): Orange variant token for swatches and previews.
- **Mono Accent** (`#8b909a`): Monochrome/neutral variant token for swatches and previews.

### Status
- **Info** (`#2d69ce` light / `#6c8cff` dark): Links, informational badges, and neutral highlights.
- **Warn** (`#b5820a` light / `#e7b53d` dark): Caution states, warnings, and pending review indicators.
- **Danger** (`#b83030` light / `#ff6b6b` dark): Errors, destructive actions, and critical admin alerts.

### Neutral
- **Paper Background** (`#fbfbfc` light / `#0e1014` dark): The page canvas.
- **Surface** (`#ffffff` light / `#16191f` dark): Cards, panels, modals, and elevated containers.
- **Surface 2** (`#f5f6f8` light / `#1b1f26` dark): Secondary surfaces, hover states, and chip/tag backgrounds.
- **Inset** (`#eef0f3` light / `#0a0c10` dark): Code panes, deeply nested views, and recessed surfaces.
- **Border** (`#e7e8ec` light / `#262b33` dark): Default dividers and card borders.
- **Border 2** (`#d8dade` light / `#343a44` dark): Stronger borders for inputs and focused states.
- **Ink** (`#15171c` light / `#f1f3f6` dark): Primary text.
- **Dim Ink** (`#565c67` light / `#a6adb8` dark): Secondary text, descriptions, and metadata.
- **Faint Ink** (`#6b7280` light / `#868d99` dark): Placeholders, disabled hints, and subtle labels.

### Named Rules
**The One Accent Rule.** Green is the only saturated color in normal use. Info, warn, and danger are reserved for status; they do not become decorative accents.

**The Light-Dark as Source Rule.** Every neutral token is defined with CSS `light-dark()`. There are no hard-coded light or dark exceptions; components inherit the theme from `:root`.

**The No-Cream Rule.** Backgrounds are cool-tinted, not warm paper or sand. Light mode is `#fbfbfc` (near-white with a cool grey tint), not beige or cream.

## 3. Typography

**Display / Body Font:** Bricolage Grotesque (with system-ui, -apple-system, Segoe UI fallbacks) — a grotesque sans with slight irregularity; functional but not sterile.
**Label / Mono Font:** Spline Sans Mono (with SF Mono, Menlo, Consolas fallbacks) — a technical monospace for machine values, slugs, labels, and code.

**Character:** One type family carries almost all UI text; the monospace is used only for machine-readable labels and code. This pairing keeps the system unified and avoids the clutter of multiple similar sans-serifs. Headings are tight and confident, body text is legible, and labels are small, uppercase, and tracked.

### Hierarchy
- **Display** (800 weight, `clamp(36px, 5vw, 56px)`, line-height 1.05, letter-spacing -0.03em): Hero headlines on the home page and major landing sections.
- **Headline** (700 weight, 26px, line-height 1.12, letter-spacing -0.02em): Section titles on browse pages, admin dashboards, and detail pages.
- **Title** (700 weight, 16.5px, line-height 1.2, letter-spacing -0.02em): Card titles, list item titles, and small headings.
- **Body** (400 weight, 15px, line-height 1.55): Paragraphs, descriptions, and form helper text. Max line length: 65–75ch.
- **Lede** (400 weight, 18px, line-height 1.6): Introductory paragraphs and hero descriptions.
- **Label** (600 weight, 11px, line-height 1, letter-spacing 0.08em, uppercase): Table headers, stat labels, footer titles, and metadata.
- **Mono** (400 weight, 12.5px, line-height 1.7): Slugs, token bands, code snippets, file paths, and counts.

### Named Rules
**The Mono-for-Machine-Values Rule.** Use Spline Sans Mono only for slugs, counts, file paths, token bands, and code. Never set body prose or headings in monospace.

**The Tight-Not-Touching Rule.** Display headings use letter-spacing as tight as -0.03em, but never tighter. Body text keeps normal spacing.

**The One-Sans Rule.** Bricolage Grotesque is the only sans-serif in use. Do not introduce a second sans for “contrast”.

## 4. Elevation

Elevation is structural, not atmospheric. At rest, surfaces are flat and separated by 1px borders and tonal shifts. Shadows appear only as a response to state: hover lifts on cards, pop overlays, modals, and toasts. The shadow vocabulary is small and deliberate; depth is communicated more by border and background contrast than by blur.

### Shadow Vocabulary
- **Ambient Low** (`0 1px 2px rgba(20, 22, 28, 0.06)` light / `0 1px 2px rgba(0, 0, 0, 0.4)` dark): Subtle elevation for buttons, chips, and small controls at rest.
- **Lift** (`0 6px 24px -6px rgba(20, 22, 28, 0.14)` light / `0 8px 30px -8px rgba(0, 0, 0, 0.6)` dark): Hover lift on cards, dropdowns, and medium overlays.
- **Pop** (`0 16px 50px -10px rgba(20, 22, 28, 0.22)` light / `0 20px 60px -12px rgba(0, 0, 0, 0.7)` dark): Modals, drawers, mobile menus, and toasts.

### Named Rules
**The Flat-by-Default Rule.** Surfaces are flat at rest. Elevation is earned through interaction (hover) or explicit layering (modal/drawer).

**The Border-Not-Shadow Rule.** Separate adjacent surfaces with 1px borders first. Shadows are for lift, not boundaries.

## 5. Components

Components are direct and tactile. They use the same corner scale, the same 1px border language, and the same two-color state model (default / hover). Admin components are denser and more scannable; public components are slightly more padded but still efficient.

### Buttons
- **Shape:** 11px radius (`var(--r-md)`), 40px height, padding 0 16px.
- **Primary:** Signal-green background (`#34d27e`), dark ink text (`#04130a`), no border. Hover: signal-green-strong (`#1f9d5b`). Active: translate down 1px.
- **Ghost:** Transparent background, dim ink (`#565c67` light / `#a6adb8` dark), no border. Hover: surface-2 background, full ink color.
- **Outline:** Transparent background, full ink, 1px border on border-2. Hover: surface-2 background.
- **Danger:** Danger-soft background (`#fbe6e6` light / `#2a1212` dark), danger text. Used for destructive admin actions.
- **States:** Focus uses a 3px ring in `color-mix(signal-green, 28% transparent)`; active translates 1px down.

### Chips
- **Shape:** Pill radius (999px), 28px height, padding 0 11px.
- **Style:** Surface-2 background, 1px border, dim ink text.
- **Selected:** Accent-soft background, accent-text color, border mixed with accent.
- **Use:** Filter chips, category shortcuts, and compact actions.

### Tags
- **Shape:** 6px radius, 22px height, padding 0 8px.
- **Style:** Surface-2 background, 1px border, dim ink, monospace font.
- **Use:** Skill tags and language labels. Optional dot before the label uses the accent color by default.

### Cards / Containers
- **Corner Style:** 16px radius (`var(--r-lg)`).
- **Background:** Surface color.
- **Border:** 1px solid border color.
- **Shadow:** None at rest; Lift shadow on hover for clickable card links.
- **Internal Padding:** 22px (`var(--card-pad)`).
- **Signature card:** The skill card has a 42px rounded icon block in accent-soft, a verified badge, a two-line clamped description, tag row, and a meta row (installs, token band, author) separated by a top border.

### Inputs / Fields
- **Shape:** 11px radius (`var(--r-md)`), 40px height, padding 0 13px.
- **Style:** Surface background, 1px border-2, full ink text.
- **Focus:** Border shifts to accent-text, 3px accent glow ring.
- **Search variant:** Larger height (56px / 66px), left search icon, rounded-lg (`var(--r-lg)`), optional kbd hint on the right.
- **Error:** Border color forced to danger, with a small danger-text message below.

### Navigation
- **Public header:** Sticky top, frosted-glass blur (`backdrop-filter: blur(14px) saturate(150%)`) over a translucent background, 1px bottom border. Brand logo + text on the left, primary nav links in the center, theme toggle + submit button + auth on the right. Mobile collapses into a drawer panel.
- **Admin sidebar:** 244px fixed/sticky sidebar, surface background, right border. Brand + admin tag at top, vertical nav links with accent-soft active state, footer with back-to-site link. Mobile slides in from the left.
- **Admin top bar:** Sticky, frosted-glass blur, 1px bottom border, page title centered, theme toggle on the right.

### Signature Component: Skill Card
- A 16px-radius card with 22px padding.
- Top row: 42px accent-soft icon block with skill initials, title + slug, and a corner star toggle.
- Body: two-line clamped description, up to four tags.
- Footer: meta row (installs, token band, author) separated by a 1px top border.
- Hover: border shifts to border-2, Lift shadow, translate up 2px.
- Star toggle is an icon button layered above the card link so it remains clickable independently.

## 6. Do's and Don'ts

### Do:
- **Do** use signal green for primary actions, verified badges, and selected filter states.
- **Do** keep body text within 65–75ch for readability.
- **Do** use `light-dark()` tokens for every color that changes across themes.
- **Do** use Spline Sans Mono for slugs, counts, file paths, token bands, and code only.
- **Do** respect `prefers-reduced-motion` by collapsing animation durations to zero.
- **Do** use 1px borders as the primary separator between surfaces.
- **Do** make the skill card the center of gravity: search → card → detail → install.
- **Do** use focus-visible rings in `accent` with a 2px offset and 3px spread.

### Don't:
- **Don't** mimic official Android/Google branding, color palette, or marketing language. This is an independent community index.
- **Don't** use soul-less corporate Apple minimalism: cold white space, sterile layouts, or anonymous luxury product pages.
- **Don't** add cute illustrations, playful mascots, ornamental flourishes, or decorative gradients.
- **Don't** use SaaS marketing-page theater: big hero metrics, gradient-text slogans, or generic “revolutionize your workflow” copy.
- **Don't** use border-left or border-right greater than 1px as a colored accent on cards, list items, or callouts.
- **Don't** use gradient text (`background-clip: text`) for headings or emphasis.
- **Don't** use glassmorphism as a default treatment.
- **Don't** pair a 1px border with a wide soft drop shadow on the same element for decoration.
- **Don't** use border-radius larger than 16px on cards or sections; reserve pill radius for tags, chips, and buttons.
- **Don't** use decorative grid backgrounds or stripe patterns unless the surface is an actual canvas or map.
- **Don't** set decorative motion as a gate to content visibility; every section must be visible by default.
- **Don't** rely on color alone to indicate status or actions; pair with text, icons, or badges.
