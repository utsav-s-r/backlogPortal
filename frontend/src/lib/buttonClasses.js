/**
 * THE button system. Every button and button-like link in the app comes from here.
 *
 * Why it exists: an audit found 44 hand-written button class strings in 31 DISTINCT geometries —
 * four radii (lg/xl/full/md), a dozen paddings, and four controls with no fill at all. Buttons
 * sitting in the same table row were different sizes and shapes, which is the first thing you see
 * and the last thing anyone thinks to grep for.
 *
 * The rule this encodes: SHAPE AND SIZE ARE FIXED; ONLY COLOUR VARIES. Two buttons beside each
 * other differ in tone and nothing else. If you need a new look, add a TONE — never a new padding,
 * radius or text size, and never a bare class string at the call site.
 *
 * Design contract (CLAUDE.local.md's no-box rule): every tone is a FILL, none has a border, and
 * every hover is a DIFFERENT fill rather than the appearance of an edge. `npm run lint:no-box`
 * fails if a border or an underline comes back.
 *
 * Width is deliberately absent — pass it. Two width utilities on one element resolve by CSS SOURCE
 * ORDER and `.w-full` is emitted after every numeric width, so the caller must own it.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */

const BASE =
  "inline-flex items-center justify-center gap-1.5 rounded-lg font-semibold " +
  "transition-colors disabled:cursor-not-allowed disabled:opacity-50";

/** Three sizes, and only three. `sm` is a control inside a table row, `md` the default, `lg` a
 *  choice on a near-empty page (404, the error boundary). */
const SIZES = {
  sm: "px-3 py-1.5 text-xs",
  md: "px-4 py-2 text-sm",
  lg: "px-5 py-3 text-sm",
};

/** Colour carries the meaning: `accent` is the thing to press, `danger` the thing that destroys.
 *  Flattening a coloured control to `neutral` loses information and is the same defect as dropping
 *  the control. Hover is always a DIFFERENT FILL, never the appearance of an edge. */
const TONES = {
  /** The workhorse. Hover lifts to the accent at low alpha — the same tint that marks a selected
   *  row, so "you can press this" and "this is chosen" read as one family. */
  neutral: "bg-surface-muted text-ink hover:bg-accent-tint",
  /** THE emphasized tone, and the one saturated thing on a resting page. `text-on-accent` resolves
   *  to the page surface, so the label flips with the theme by itself — never hardcode it white. */
  accent: "bg-accent text-on-accent hover:opacity-90",
  /** Destructive. A FILL, not red text: a delete must not be mistakable for an error message, which
   *  is what happened while both were `text-red-600`. */
  danger: "bg-alert text-on-accent hover:opacity-90",
  /** No fill until hovered — for a control in the top band or the rail, where a filled pill would
   *  compete with the page's own primary action. Renamed from `onNavy` when the chrome colour was
   *  removed: there is no navy ground any more, so white alphas had nothing to sit on. */
  quiet: "text-ink hover:bg-surface-muted",
};

/**
 * FOUR things legitimately sit outside this system, and a later pass should not "fix" them:
 * icon-only square nav buttons on the navy band/rail (`size-10`, no label to size against), the
 * five role-selection CARDS on the staff login (icon + title + blurb, not a control in a row), the
 * two icon-only dismiss/close buttons (`p-1.5`), and BulkProgressionTab's full-width disclosure
 * header. Each is internally consistent with its own pair; none is a button sitting next to a
 * button at a different size, which is the thing this file exists to prevent.
 *
 * @param tone one of TONES — the only thing that should vary between adjacent buttons
 * @param size one of SIZES — `md` unless the button sits in a table row (`sm`)
 */
export function btn(tone = "neutral", size = "md") {
  return `${BASE} ${SIZES[size]} ${TONES[tone]}`;
}
