# Accessibility Report

Keyboard-visible focus states, reduced-motion handling, mobile-friendly navigation, and a
light/dark theme driven entirely by CSS custom properties (see the token vocabulary at the top of
`src/index.css`).

**Provenance:** the keyboard/motion sections date from the original pass (2026-05-09) and have not
been re-verified since. The contrast section was re-measured on 2026-08-10 — read the scope note
there before trusting it.

## Contrast

Ratios below are computed (WCAG 2.1 relative luminance), not estimated. AA needs **4.5:1** for
normal text, **3:1** for large text and for a UI component against its background.

| Colour | Role | Measured |
| --- | --- | --- |
| maroon `#91191C` (`--color-primary`) | emphasis, borders, focus ring; CTA fill in light | 8.90 with white text |
| navy `#242A52` (`--color-secondary`) | structure, headers, label text on light | 13.74 with white text |
| salmon `#F0888B` (`--text-on-dark-primary`) | maroon's dark-mode text tint; CTA fill in dark | 7.00 against the dark page |
| red-600 `#DC2626` | destructive actions (Reject, Delete) | 4.83 with white text |
| `#F5F5F5` (`--surface-muted`) | section surfaces | — |

**Primary CTA** (`--color-cta`) is theme-aware, and is the one token that goes *brighter* in dark:
a CTA must out-contrast its page, and the dark page is itself dark navy, so brand maroon or navy
would sink into it (1.92 and 1.24 — both under the 3:1 floor).

| | Fill | Label | Label contrast | Fill vs page |
| --- | --- | --- | --- | --- |
| Light | maroon `#91191C` | white | 8.90 | 8.90 |
| Dark | salmon `#F0888B` | dark navy `#141A35` | 7.00 | 7.00 |

The light-fill/dark-label pairing in dark mode is also what separates the primary CTA from the
destructive red, which is a solid fill with a white label — the raw colour distance between them
is only 1.98, so the label does the distinguishing work.

> Superseded 2026-08-10: this file previously stated that CTA pink `#ED145B` was "paired with white
> text for strong contrast". That pairing measured **4.33**, i.e. it failed AA for normal text. The
> pink has been removed from the codebase entirely.

### Scope of the contrast re-measurement

Verified by rendering and measuring computed styles in **both themes**:

- Home / landing page — full sweep of text elements; no failures remain
- Student login
- Admin login (including the focused skip link)

**Not re-measured** — no failures known, but no evidence either: the registration page, admin
dashboard, manage-subjects, manage-students, departments, users, exam-cycles, and the tabbed
sub-views. These sit behind authentication.

Automated contrast checking has real false-positive modes (translucent overlays, gradients, text
over images) and cannot judge whether something merely looks wrong. Treat any future sweep's
output as candidates to triage, not a verdict.

## Keyboard Path Checklist

*From the original pass; not re-verified.*

- `Tab` reaches the skip link on each major page.
- `Tab` moves through navigation, form controls, and action buttons in a predictable order.
- Focus states are visible on links, buttons, inputs, and selects (global `:focus-visible` outline
  in `index.css`, plus a `focus-visible` ring on `MagneticCta`).
- Submit and verify actions are operable by keyboard without pointer input.
- Mobile bottom navigation stays available without blocking primary content.

## Motion and Interaction

- Smooth scrolling for section jumps, disabled under `prefers-reduced-motion`, which also clamps
  animation and transition durations to `0.01ms`.
- CTA buttons give press feedback via `active:scale-[0.98]`.

> Corrected 2026-08-10: this file previously described a "subtle magnetic effect" on CTA buttons.
> `framer-motion` and the `useMagnetic` hook were removed in the 2026-07 UI pass; `MagneticCta`
> keeps the name but has no magnetic translate.

## Notes

- Backend endpoints unchanged.
- Cypress covers registration and admin verification flows, but **every spec stubs its API calls
  and none asserts a computed style** — no automated test protects contrast or theming today.
