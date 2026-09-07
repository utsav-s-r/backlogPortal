/**
 * The button shells, for the buttons that are NOT PrimaryCta. Same job as formClasses.js does for
 * inputs, and the same reason: these strings were typed out at ~18 sites across 8 files, and drift
 * between them fails silently — the suite asserts nothing about styles, so a divergence shows up
 * only as one button looking wrong on one page, in one theme.
 *
 * Shape of every entry, and the design contract they encode (see CLAUDE.local.md's no-box rule):
 *   - a FILL, never a border. Nothing here may gain `border-*`; `npm run lint:no-box` fails if it does.
 *   - hover is a DIFFERENT fill, never the appearance of an edge. `bg-surface-muted` hovers to
 *     `bg-primary-tint`; `bg-red-50` hovers to `bg-red-100` (which has its own dark re-tint at 0.20
 *     against red-50's 0.12, so the step survives dark mode).
 *   - a coloured control keeps its colour: DANGER_SM stays red because a red-texted delete that
 *     becomes neutral grey has lost the meaning the colour carried.
 *
 * SIZE IS THE ONLY AXIS. If you need a different width, pass it — width is not in here, for the
 * reason formClasses.js spells out: two width utilities on one element resolve by CSS SOURCE ORDER,
 * and `.w-full` is emitted after every numeric width.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */

const BASE = "inline-flex items-center rounded-lg font-semibold transition-colors";

/** The workhorse: a form/panel action sitting beside a PrimaryCta. */
export const BTN_QUIET =
  `${BASE} gap-2 bg-surface-muted px-4 py-2 text-sm hover:bg-primary-tint disabled:opacity-60`;

/** Full-width-ish choice on a near-empty page (404, the error boundary). */
export const BTN_QUIET_LG =
  `${BASE} justify-center bg-surface-muted px-5 py-3 text-sm text-ink hover:bg-primary-tint`;

/** Row-level action that carries a longer label, so it takes the larger type. */
export const BTN_SMALL_WIDE =
  `${BASE} gap-1 bg-surface-muted px-3 py-2 text-sm hover:bg-primary-tint`;

/** The tightest one: a control sitting INSIDE a dense row beside its own value (the dashboard's
 *  phone Edit, a semester quick-select). It is what an underlined text link becomes — decision 15:
 *  if it behaves as a button it must look like one, and looking like one means a fill. */
export const BTN_ROW =
  `${BASE} gap-1 bg-surface-muted px-2.5 py-1.5 text-xs hover:bg-primary-tint`;

/** Destructive row action. Red is the meaning, not decoration — do not neutralise it. */
export const BTN_DANGER_SM =
  `${BASE} gap-1 bg-red-50 px-3 py-1.5 text-xs text-red-600 hover:bg-red-100 disabled:opacity-60`;
