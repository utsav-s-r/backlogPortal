# ADR: UI theming — CSS-first tokens, `data-theme`, and the contrast constraints

Status: Accepted — token utilities adopted 2026-08-10; palette rebuilt to three colours 2026-09-08.
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
`bg-surface-1`, `bg-surface-muted`, `text-ink`, `text-ink-muted`, `border-stroke`, and the three
colours `bg-accent` / `bg-success` / `bg-alert` (plus their `-tint` and `text-on-accent`).
Dark values are redefined in the `[data-theme="dark"]` override block.

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

## Three colours, because a colour is a meaning

`accent` = anything you can act on. `success` = went through. `alert` = went wrong or undoes
something. Everything else is a neutral derived from `#201e3c`. A fourth colour is a new *meaning*,
not a new style — if you cannot say in one word what it means, it does not belong.

Each colour is **one token name with a light and a dark value**; components write `bg-accent` and
the theme resolves it. Dark values are **desaturated as well as lightened** — an accent that is only
lightened glows against the ground. `-tint` variants are the base colour at low alpha rather than
separate hexes, so a tint cannot drift from the colour it belongs to.

`--color-on-accent` is defined once as `var(--color-surface-1)`, so the label on any filled colour
flips with the theme by itself.

**Deleted 2026-09-08:** `--color-primary`, `--color-primary-ink`, `--color-cta`, `--color-cta-text`,
`--color-secondary`, the `[data-theme="dark"] .bg-red-50` re-tint, and `.admin-login-heading`.
Palette classes (`bg-red-50`, `text-red-600`, `accent-red-600`) are gone from `src` entirely.

## Two defects this replaced

**Surfaces collapsed.** `surface-1`, `surface-muted` and the chrome token all resolved to `#201e3c`
in dark — a contrast ratio of **1.00**. Every input, filter panel and neutral button was literally
the same colour as the page across 47 usages, while filled buttons beside them looked correct. That
asymmetry is what made the UI read as broken. In dark mode a raised surface goes **lighter** than the
page (tonal elevation), never equal or darker.

**One class carried six meanings.** `bg-red-50` was simultaneously the selected state, positive
status, negative status, the destructive action, an informational notice, and decoration. So a
chosen subject, an "Open" badge and a Delete button were painted identically. Related:
`outcomeBadgeClass` returned the same classes for VERIFIED and REJECTED, and `batchStatus` gave
CREATED and ERROR one colour — opposite states were indistinguishable.

**A blanket `body *` colour rule must never be added.** One existed and made every text token inert:
the utilities still applied, the colour never rendered, and nothing failed. `index.css` is unlayered,
so such a rule outranks every utility.

## There is no chrome colour

The top band is `bg-surface-1` with a `border-b`; the sidebar is `bg-surface-muted` with a
`border-r`. Nothing is painted to look important. `#201e3c` is the **dark-mode page background and
nothing else** — that is the one fixed colour, and it is scoped to dark. Light mode is free of it
except as the near-black it resolves to for text.

## Contrast, measured

Verified across 21 views in both themes: 0 text failures, 0 controls invisible against their
surroundings, 0 mismatched sibling buttons. Every pairing clears its floor — 4.5:1 for body text,
3:1 for a fill that must read as an object.

Status banners stay a component (`AlertBanner`) for the original reason: a hand-typed tint is a place
a one-off can slip in with no build, lint or test failure. Both of its classes are theme-aware tokens
now, so there is no dark re-tint to keep in sync — **a new tone needs a token, not a rule.**
`error` and `warning` deliberately share `alert-tint`; there is no warning colour, and adding amber
would be a new meaning.

## Trade-offs (accepted)

- Utility re-tints (`[data-theme="dark"] .bg-amber-50`) are gone — every colour is a theme-aware
  token, so there is nothing to keep in sync.
- `--hero-text` survives as a raw var because `HeroSection` reads it from an inline `style={{}}`,
  which no `[data-theme]` rule can reach.
- `error` and `warning` share one fill: the palette has three colours and none of them means
  "warning".

## Verifying a change here

The test suite proves nothing about CSS. Measure the **rendered DOM** on every affected page, in
both themes: walk up to the nearest opaque background, compute contrast for elements holding their
own text, and flag controls whose fill matches their surroundings. Assert each page actually
**loaded** — an unauthenticated route redirects to a login screen and then passes trivially. Add a
second audit for what the first cannot see (sibling buttons compared on height, radius, font-size).
A token that has silently become `undefined` still renders a plausible-looking page.

## Related

- `frontend/src/index.css` (its opening block is the token vocabulary — read it before adding UI),
  `frontend/src/context/ThemeContext.jsx`, `frontend/src/components/ui/ThemeToggle.jsx`.
- No motion libraries: none is a dependency, and none should be added; `PrimaryCta` is a plain
  styled button/link with `active:scale-[0.98]`.
