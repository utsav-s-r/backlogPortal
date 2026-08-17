# ADR: UI theming — CSS-first tokens, `data-theme`, and the contrast constraints

Status: Accepted — token utilities adopted 2026-08-10; theme system in place earlier.
Branch: `rejectStatus`.

## Context

The portal ships light and dark themes. Tailwind v4 is **CSS-first**: there is no
`tailwind.config.js` and no `postcss.config.js` in this project — the build runs through
`@tailwindcss/vite`, and adding a JS config does nothing unless a `@config` directive opts in.
So every theming decision lives in `frontend/src/index.css`.

There is **no automated safety net for any of this**: Cypress asserts no styles, and a token
rename can break every page while the whole suite stays green.

## Decision

**1. `data-theme` on the root element is the source of truth.** `ThemeContext` reads
`localStorage["portal-theme"]`, falls back to `prefers-color-scheme`, and stamps
`document.documentElement.dataset.theme`. It also toggles a `dark` class, kept as a conventional
hook — **nothing reads it**.

**2. Tokens are declared in `@theme static`, which generates real Tailwind utilities.** Use
`bg-surface-1`, `text-ink`, `border-stroke`, `bg-primary`, `text-primary-ink`, `bg-cta` and so on.
Dark values are redefined in `[data-theme="dark"]` override blocks.

**3. Do not write `x-[var(--token)]` arbitrary values.** ~800 of them across 28 files were
converted in one pass. That syntax now signals "one-off, outside the design system". If a value has
no utility, **add the token to `@theme`** instead.

**4. Components are theme-agnostic.** `ThemeToggle` is the only legitimate `useTheme()` consumer —
and it picks a sun/moon icon, not a color. Never branch on theme in JS, and never put a hex color
in an inline `style={{}}`: inline styles outrank every stylesheet rule, so a `[data-theme="dark"]`
override cannot reach them and a class-based audit cannot see them.

## Three mechanisms that look like defaults but are load-bearing

**`@theme static`, never plain `@theme`.** Plain `@theme` tree-shakes tokens it cannot see used —
and a `[data-theme="dark"]` override does **not** count as a use. A dark-only-overridden token
would vanish from `:root` and be undefined in *light*, with no build, lint or test failure.

**Never `@theme inline`.** It bakes literal values into each utility instead of referencing the
custom property, which breaks theme switching outright.

**`--shadow-soft` is deliberately NOT in `@theme`.** Tailwind decomposes a shadow token at build
time (to support the `shadow-red-500/50` color-modifier feature), compiling `.shadow-soft` to a
baked-in light literal and killing the dark override across every usage — again silently. It stays
a hand-written class in `:root`.

**`index.css` is deliberately unlayered.** Its rules therefore outrank everything in Tailwind's
`utilities` layer regardless of specificity. That is what lets the hand-written `.shadow-soft` beat
Tailwind's shadow utilities and the `h1–h4` Playfair rule beat a `font-serif` utility. **Do not
wrap this file in `@layer`.**

**The `dark:` variant is redefined.** `index.css` declares
`@custom-variant dark (&:where([data-theme="dark"], [data-theme="dark"] *));`. Without it, v4's
stock `dark:` follows `prefers-color-scheme` and **ignores the toggle**. Current usage of `dark:`
is zero; tokens plus override blocks are the house style.

## Status banners are a component, because the palette classes are a hidden contract

Mechanism (2) — the `[data-theme="dark"]` utility re-tints for the red/amber/green status tints —
carries an implicit contract that nothing enforces: *a banner may only use classes that block
covers*. A class without a re-tint ships as a bright light-mode patch on the dark navy page, and
nothing catches it (Cypress asserts no styles, the build does not run eslint, eslint does not read
class strings).

For a long time that contract was re-typed by hand at **32 separate banner sites**, which had drifted
to two red text shades, four amber ones, three paddings, two radii and an inconsistent
`font-medium`. The revealing detail: **dark mode had already collapsed almost all of it** — the
re-tints map `text-red-600` and `text-red-700` to the same `#f87171`, and all four amber shades to
`#fbbf24`. The sprawl was visible in **light only**, so unifying it removed no distinction a user
could perceive in dark.

Decision (2026-08-16): `components/AlertBanner.jsx` owns the contract. `TONES` is the one allowed
class triple per tone (`error`/`warning`/`success`), so there are exactly **9** palette classes in
the banner system and each has a matching re-tint. Shades are the darkest available with a re-tint
— dark is unchanged by construction, light gains contrast (red-700 on red-50 is 6.9:1 against
red-600's 4.8:1). Adding a tone means adding its re-tints in the same edit.

Explicitly **not** banners, and deliberately left hand-rolled: destructive buttons, status pills, and
bare inline error text (`text-sm font-medium text-red-600`, no fill). Routing those through
`AlertBanner` would put a filled box where the design wants a bare label.

## The CTA colour is theme-aware — and that is not decoration

`--color-cta` is the one token that goes **brighter** in dark: `var(--color-primary)` maroon
`#91191c` in light, `var(--text-on-dark-primary)` salmon `#f0888b` in dark. `--color-cta-text` is
defined **once** as `var(--color-surface-1)` — already white in light and dark navy `#141a35` in
dark — so the label flips for free.

Measured (WCAG 2.1, recomputed from the live token values):

| Pairing | Ratio | Floor |
|---|---|---|
| Light CTA: maroon fill, white label | **8.90** | 4.5 |
| Dark CTA: salmon fill, navy label | **7.00** | 4.5 |
| Maroon fill on the dark page | **1.92** | 3.0 (non-text) — fails |
| Navy fill on the dark page | **1.24** | 3.0 (non-text) — fails |
| `text-amber-600` on white, small text | **3.19** | 4.5 — fails; use `amber-700` |
| `text-red-600` on white, small text | **4.83** | 4.5 |

That amber row is why small amber status text is `text-amber-700`, never `-600` — measured when the
batch-result table needed an amber that sits beside `text-red-600` without dropping below AA. Both
shades share one `[data-theme="dark"]` re-tint, so it is a light-only correction. (Recorded here
2026-08-16 when the conflict-status styles that carried this note were deleted; `text-amber-700` is
still live in `SemesterTimeline`'s "not set" flag.)

Those last two rows are why a single flat brand colour cannot work: the dark page *is* dark navy,
so a maroon or navy button sinks into it. Brightening maroon until it clears the 3:1 floor lands on
roughly `#da262a` — about 1.01 from the destructive `bg-red-600`, i.e. indistinguishable from
Reject/Delete. Salmon-with-a-dark-label versus solid-red-with-a-white-label is what keeps the
primary and destructive actions apart; raw colour distance alone is only ~1.98, so **the label does
the work**. Do not "simplify" this back to one colour.

**There is no pink.** A standalone pink CTA measures 2.08 against white here and fails AA outright.
Four hardcoded copies of the old pink were replaced by `--color-cta-glow`, `--selection-bg`, the
dark hero-badge tints, and `--hero-blob-2` (now navy — the accent is maroon, so both blobs would
otherwise be one hue).

## Trade-offs (accepted)

- Some dark rules re-tint Tailwind utilities directly (e.g. `[data-theme="dark"] .bg-amber-50`)
  rather than adding `dark:` variants at every call site. Accepted: fewer edits, one place to look.
- Solid destructive fills (`bg-red-600/700` with white text) are theme-**independent** by design.
- Two semantic one-offs (`.admin-login-heading`, `.login-back-link`) remain hand-written and win by
  being unlayered.

## Verifying a change here

The test suite proves nothing about CSS. Use the computed-style snapshot method — capture resolved
values before and after, **in both themes** — rather than eyeballing a screenshot. A token that has
silently become `undefined` still renders a plausible-looking page.

## Related

- `frontend/src/index.css` (its opening block is the token vocabulary — read it before adding UI),
  `frontend/src/context/ThemeContext.jsx`, `frontend/src/components/ui/ThemeToggle.jsx`.
- No motion libraries: `framer-motion` is not a dependency; `MagneticCta` is a plain styled
  button/link with `active:scale-[0.98]`.
