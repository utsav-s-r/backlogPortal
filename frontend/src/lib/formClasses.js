/**
 * The one form-control shell — every form control uses it, no local `inputClass` constants and no
 * inline copies. Drift here fails silently: the suite asserts nothing about styles and dark mode
 * collapses many of these tints, so a divergence shows in LIGHT only, and often only while a
 * control happens to be disabled.
 *
 * WIDTH IS NOT IN HERE, deliberately. Two width utilities on one element are resolved by CSS
 * SOURCE ORDER, not by the order they appear in the class string — and `.w-full` is emitted after
 * every numeric width, so `w-full` silently beats an explicit `w-32`. Keeping width out means a
 * caller that wants a fixed width just passes it and gets it. Use FIELD_INPUT for the ordinary
 * full-width form-grid control; use FIELD_CONTROL plus your own width for anything sized.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */

/** The shell: border, padding, type scale, focus ring, placeholder and disabled treatment. */
export const FIELD_CONTROL =
  "rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none " +
  "transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 " +
  "focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

/** The label above a control. Its own export because the same string is needed by BOTH
 *  components/ui/Field (editable) and components/ui/ReadOnlyField (display), which are deliberately
 *  separate components — without this the one class string they share is typed twice, which is how
 *  the control half drifted in the first place. Callers add `text-ink` / `text-ink-muted`. */
export const FIELD_LABEL = "text-xs font-semibold uppercase tracking-[0.08em]";

/** The common case — a control filling its form-grid cell. */
export const FIELD_INPUT = `w-full ${FIELD_CONTROL}`;
