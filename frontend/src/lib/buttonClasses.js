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

/** Colour carries the meaning, so a control keeps the colour it already had: a red-texted delete
 *  becomes a red fill, a navy one stays navy. Flattening a coloured control to `neutral` loses
 *  information and is the same defect as dropping the control. */
const TONES = {
  neutral: "bg-surface-muted text-ink hover:bg-primary-tint",
  /** THE emphasized tone — there is exactly one, and every emphasized action uses it. There were
   *  three (`cta`, `primary`, `navy`): `primary` resolved to the SAME maroon as `cta` in light, and
   *  `navy` put a second accent colour next to it, so one row could show a navy button beside a
   *  maroon one for no reason a reader could infer. `--color-cta` is the survivor because it is the
   *  only theme-aware one — it goes BRIGHTER in dark, where the maroon is nearly invisible against
   *  the navy page. */
  accent: "bg-cta text-cta-text hover:bg-primary",
  danger: "bg-red-50 text-red-600 hover:bg-red-100",
  /** Confirming a destructive action — solid, because it is the point of no return. */
  dangerSolid: "bg-red-600 text-white hover:bg-red-700",
  /** On the navy top band, where the ground is dark in BOTH themes, so these are white alphas and
   *  need no [data-theme="dark"] counterpart. */
  onNavy: "bg-white/20 text-white hover:bg-white/30",
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
