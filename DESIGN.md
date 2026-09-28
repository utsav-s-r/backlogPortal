---
name: MSRIT Backlog Portal
description: Backlog-exam registration and verification for Ramaiah Institute of Technology.
colors:
  lamp-amber: "#a15c00"
  lamp-amber-dark: "#e6b866"
  lamp-amber-tint: "rgba(161, 92, 0, 0.17)"
  cleared-green: "#1f6b4d"
  cleared-green-dark: "#74c49f"
  cleared-green-tint: "rgba(31, 107, 77, 0.13)"
  rejection-rose: "#b0304e"
  rejection-rose-dark: "#ef8098"
  rejection-rose-tint: "rgba(176, 48, 78, 0.13)"
  ledger-paper: "#fcfbfd"
  night-registry: "#201e3c"
  desk-lavender: "#f0eef6"
  desk-lavender-dark: "#2c2a51"
  hairline-lilac: "#dcd8e7"
  hairline-lilac-dark: "#45406f"
  indigo-ink: "#1d1b33"
  indigo-ink-dark: "#eceaf4"
  faded-ink: "#5f5980"
  faded-ink-dark: "#a9a3c6"
typography:
  display:
    fontFamily: "Playfair Display, Georgia, serif"
    fontSize: "clamp(2.5rem, 5vw, 3.75rem)"
    fontWeight: 600
    lineHeight: 1.15
  headline:
    fontFamily: "Playfair Display, Georgia, serif"
    fontSize: "1.875rem"
    fontWeight: 600
    lineHeight: 1.2
  title:
    fontFamily: "Playfair Display, Georgia, serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.33
  body:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.45
  label:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.33
    letterSpacing: "0.08em"
rounded:
  lg: "8px"
  dialog: "16px"
  full: "9999px"
spacing:
  gutter-sm: "16px"
  gutter-md: "24px"
  gutter-lg: "32px"
  page-y: "32px"
  control-x: "14px"
  control-y: "10px"
components:
  button-primary:
    backgroundColor: "{colors.lamp-amber}"
    textColor: "{colors.ledger-paper}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
    typography: "{typography.label}"
  button-neutral:
    backgroundColor: "{colors.desk-lavender}"
    textColor: "{colors.indigo-ink}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  button-neutral-hover:
    backgroundColor: "{colors.lamp-amber-tint}"
  button-danger:
    backgroundColor: "{colors.rejection-rose}"
    textColor: "{colors.ledger-paper}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  button-quiet:
    textColor: "{colors.indigo-ink}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  button-quiet-hover:
    backgroundColor: "{colors.desk-lavender}"
  input:
    backgroundColor: "{colors.desk-lavender}"
    textColor: "{colors.indigo-ink}"
    rounded: "{rounded.lg}"
    padding: "10px 14px"
  banner-error:
    backgroundColor: "{colors.rejection-rose-tint}"
    textColor: "{colors.rejection-rose}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  banner-success:
    backgroundColor: "{colors.cleared-green-tint}"
    textColor: "{colors.cleared-green}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  status-pill:
    rounded: "{rounded.full}"
    padding: "2px 10px"
  nav-item:
    textColor: "{colors.indigo-ink}"
    rounded: "{rounded.lg}"
    padding: "10px 12px"
  nav-item-current:
    backgroundColor: "{colors.lamp-amber-tint}"
---

# Design System: MSRIT Backlog Portal

## Overview

**Creative North Star: "The Registrar's Desk"**

The portal is an orderly college office rendered on screen. Surfaces are quiet, paper-like and
faintly violet; text is indigo ink; one warm colour, Lamp Amber, marks whatever you can act on.
Nothing is painted to look important. The college crest supplies identity, so there is no chrome
colour, no branded header bar and no decorative panel. Serif headings give the pages the voice of
an official document, and the body is a plain, legible sans.

Density follows the surface. Student pages are spacious and single-purpose, and admin pages are
table-first working screens. Both share one vocabulary: separation comes from a fill, spacing or a
hairline rule, never from an outlined box. Components are calm and exact. Sizes are fixed, only
colour varies, and hover is always a different fill rather than an edge appearing. Motion is
minimal: colour transitions, a slight press on the primary action, and a delayed fade for slow
route loads.

Dark mode is the same desk at night. The Night Registry navy (#201e3c) is the page, raised surfaces
get lighter rather than darker, and every colour is desaturated as well as lightened so nothing
glows.

**Key Characteristics:**
- Three colours with fixed meanings (act, went through, went wrong); everything else is neutral.
- Fills, spacing and hairlines, never outlines.
- One button geometry; tone is the only variable.
- Playfair Display headings over Inter body.
- Light and dark are equal citizens; every colour token has both values.

## Colors

A violet-tinted neutral desk with exactly three semantic colours, each one a meaning rather than a
style.

### Primary
- **Lamp Amber** (#a15c00 light / #e6b866 dark): anything you can act on, including primary
  buttons, the active tab, the focus ring, the current sidebar row and a row still waiting for
  action. Its tint (17% alpha) means *selected or pending* and doubles as the neutral button's hover,
  so "you can press this" and "this is chosen" read as one family.

### Secondary
- **Cleared Green** (#1f6b4d light / #74c49f dark): something went through, such as VERIFIED,
  CREATED or registrations open. Used as text on its 13% tint for pills and banners.

### Tertiary
- **Rejection Rose** (#b0304e light / #ef8098 dark): something went wrong or will be undone, such
  as REJECTED, ERROR, delete, reject or a field error. Destructive buttons use it as a solid
  fill, so a delete can never be mistaken for an error message.

### Neutral
- **Ledger Paper** (#fcfbfd): the light page and top band; also the label colour on any filled
  colour (`on-accent`), so labels flip with the theme automatically.
- **Night Registry** (#201e3c): the dark page, and in light mode the wordmark colour. It is used as
  the dark page only, never as a panel or chrome fill.
- **Desk Lavender** (#f0eef6 light / #2c2a51 dark): one step up from the page. Used for inputs, the
  sidebar, filter panels, neutral buttons and unselected chips. It must never equal the page.
- **Hairline Lilac** (#dcd8e7 light / #45406f dark): hairline rules only, between rows, sections
  and chrome edges.
- **Indigo Ink** (#1d1b33 light / #eceaf4 dark): body text and headings. The dark value is
  off-white, never #ffffff.
- **Faded Ink** (#5f5980 light / #a9a3c6 dark): labels, help text, captions and table headers.

### Named Rules
**The Three Meanings Rule.** Amber = act, green = went through, rose = went wrong. A fourth colour is
a new meaning, not a styling choice. If it can't be named in one word, it doesn't ship. Warning
shares rose's fill instead of introducing amber-as-warning.

**The Tint-Is-Alpha Rule.** Every `-tint` is its base colour at low alpha, never a separate hex, so a
tint can never drift from the colour it belongs to.

**The Up-Is-Lighter Rule.** In dark mode a raised surface is lighter than the page, never equal and
never darker.

## Typography

**Display Font:** Playfair Display (with Georgia, serif)
**Body Font:** Inter (with system-ui, sans-serif)

**Character:** A bookish serif for headings gives each page the authority of a printed college
form. A neutral grotesque carries everything read or operated. Weight does the hierarchy work, with
600 for headings and labels and 400 for body.

### Hierarchy
- **Display** (600, clamp(2.5rem, 5vw, 3.75rem), 1.15): the homepage hero headline only.
- **Headline** (600, 1.875rem, 1.2): page `<h1>` on student, account and near-empty pages.
- **Title** (600, 1.5rem, 1.33): admin page `<h1>` and section headings (`<h2>`–`<h4>` are all
  Playfair).
- **Body** (400, 0.875rem, 1.45): nearly all UI text, tables and form values. The hero subtitle
  runs at 1.05rem / 1.65 within a 36rem measure.
- **Label** (600, 0.75rem, 0.08em tracking, UPPERCASE): field labels and table column headers, in
  Faded Ink. The homepage status badge widens this to 0.12em.

### Named Rules
**The Serif Speaks First Rule.** Every heading is Playfair Display, and nothing else is. A serif in
body copy or a sans headline breaks the document voice.

**The 16px Floor Rule.** Below 40rem every text input, select and textarea renders at 16px, or iOS
zooms the page on focus and never zooms back out.

## Layout

Two shells. Student and public pages use a centred single column (`PageLayout`) with a top band
holding the crest and a few quiet pill links. The column is capped by content: `max-w-md` for
login, up to `max-w-5xl` for registration. Admin pages use a fixed left sidebar (260px, collapsing
to a 68px icon rail on desktop and an off-canvas drawer below 768px) beside a content column capped
around `max-w-7xl`.

Gutters step 16px → 24px → 32px at the 640px and 1024px breakpoints, and pages open with 32px of
top padding. Every admin page opens with an `<h1>` plus a one-sentence subtitle. Record lists are
real `<table>`s with uppercase column headers and hairline row rules. Wide tables scroll
horizontally inside their own container rather than squeezing, and auto layout (`min-w-full`) is
preferred to fixed columns. Tabbed admin pages put a wrap-flowing row of tone-switched buttons
above a single tab panel.

**The Measure-Against-the-Window Rule.** Mobile layout is verified at 375px wide. Widths are
measured against the real window, never the viewport constant, because Linux scrollbars eat ~15px.

## Elevation & Depth

Flat by default, with depth expressed through tonal steps: page → Desk Lavender → Lamp Amber tint.
A single shadow token exists and means only one thing, *above the page*. It appears in exactly
three places: the primary CTA, the homepage process-step markers and the registration-history
dialog.

### Shadow Vocabulary
- **Soft lift** (`box-shadow: 0 16px 40px rgba(0,0,0,0.14)`; dark `0 18px 45px rgba(0,0,0,0.35)`):
  the primary call to action, floating dialogs and hero step markers. Nothing else.

### Named Rules
**The Shadow-Means-Above Rule.** A shadow is never an outline substitute. If it is separating
something from the page it sits on, use a fill instead.

## Shapes

Gently rounded throughout. Buttons, inputs, banners, nav rows and tooltips share one 8px radius, so
everything operable has the same silhouette. Fully round is reserved for status pills, avatars and
icon discs, step markers and the theme switch. The only larger radius is the modal dialog (16px).
Borders do not exist as a shape device: no element is drawn with a four-sided outline, and rules
appear only as single-edge hairlines (`border-t`, `border-b`, `border-r`).

## Components

Calm and exact: one geometry, fixed sizes, colour as the only variable.

### Buttons
- **Shape:** gently rounded (8px), semibold label, icon gap 6px.
- **Sizes:** three only. `sm` (12px text, 6/12px padding) for controls in a table row; `md`
  (14px, 8/16px), the default; `lg` (14px, 12/20px) for choices on near-empty pages.
- **Accent:** Lamp Amber fill, `on-accent` label; hover drops to 90% opacity.
- **Neutral:** Desk Lavender fill, Indigo Ink label; hover becomes Lamp Amber tint.
- **Danger:** Rejection Rose fill, `on-accent` label; hover 90% opacity.
- **Quiet:** no fill until hover (Desk Lavender), for the top band and sidebar rail.
- **Primary CTA:** the accent button plus Soft lift and a press-in (`scale(0.98)` on active).
  It is the only button with a shadow.
- **Disabled:** 50% opacity, not-allowed cursor.
- **Focus:** a global 2px Lamp Amber outline at 2px offset on every focusable element; buttons add
  no local ring.

### Chips / Status Pills
- **Style:** fully round, 11–12px semibold, 2px/10px padding. The fill is the status tint and the
  text is the status colour (green for verified or created, rose for rejected, Desk Lavender with
  Faded Ink for neutral or unassigned, amber tint for pending or selected).
- **Homepage status badge:** the same pill, uppercase with 0.12em tracking, green tint when
  registration is open and lavender when closed.

### Cards / Containers
- **Background:** there are no cards. Panels are a Desk Lavender fill on the page, and a group of
  records is a table.
- **Dialog:** Ledger Paper, 16px radius, 24px padding, Soft lift, max 90svh with an internal scroll.

### Inputs / Fields
- **Style:** Desk Lavender fill, no border, 8px radius, 10/14px padding, 14px text (16px below
  40rem), placeholder in Faded Ink.
- **Label:** uppercase Label style above the control; the `<label>` wraps its control.
- **Focus:** 2px Lamp Amber ring.
- **Disabled:** 60% opacity. Read-only values use the same label over plain text, never an inert
  input.

### Alert Banners
- **Style:** 8px radius, status tint fill and status text colour, no border. The default size is
  12/16px padding at 14px, and the compact size is 8/12px at 12px for inline notes.
- **Tones:** error and warning share rose, and success uses green.

### Navigation
- **Sidebar:** Desk Lavender with a right hairline. Rows are 14px semibold at 10/12px padding with
  an 8px radius and a leading icon. Hover fills with the page colour, and the current row carries
  the Lamp Amber tint. A hairline divider separates groups. The identity block (username + role) sits at the foot.
  Collapsed, the rail shows icons with an Indigo-Ink tooltip.
- **Top band:** page-coloured with a bottom hairline, the crest wordmark (SVG in the wordmark
  colour) and quiet pill links on the other side.
- **Tabs:** a wrapping row of `md` buttons, accent for the current tab and neutral for the rest,
  implemented as the full ARIA tabs pattern.

### Registration Table (signature)
The admin workhorse. Header row in the uppercase Label style in Faded Ink over a bottom hairline,
body at 14px with 12/16px cell padding and hairline row rules. Status shows as a pill, and actions
are `sm` buttons (accent Verify, danger Reject) inline in the row.

## Do's and Don'ts

### Do:
- **Do** reach for a token utility (`bg-surface-muted`, `text-ink-muted`, `bg-accent-tint`) for
  every colour, and add a token to `index.css` when none fits.
- **Do** separate with a fill, spacing or a single-edge hairline in Hairline Lilac.
- **Do** build every button from the shared `btn(tone, size)` system and every labelled control
  from `Field`.
- **Do** give every colour token both a light and a dark value, and check both themes.
- **Do** keep motion to colour transitions (150–200ms), the CTA press and the 300ms-delayed route
  fade, all of which collapse under `prefers-reduced-motion`.
- **Do** open every admin page with a Playfair `<h1>` and a one-sentence subtitle.

### Don't:
- **Don't** draw a four-sided outline (`border border-stroke`, `rounded-xl border`) or underline
  links on hover. `npm run lint:no-box` fails on it.
- **Don't** add a fourth colour, a Tailwind palette class (`bg-red-50`) or a raw hex.
- **Don't** use a shadow for anything but "above the page".
- **Don't** give two adjacent buttons different sizes or radii.
- **Don't** use pure white text in dark mode, or let a raised surface equal the page.
- **Don't** add flashy animation or a motion library.
- **Don't** paint the top band or sidebar in a brand colour. The crest is the identity.
