// The one status banner. Replaced 32 hand-rolled copies whose only real variance was spelling.
// Routing them through here is what stops a one-off tint reaching a page: nothing fails when one
// does — the build passes, lint passes, and Cypress asserts nothing about styles.
//
// Bare inline error text (`text-sm font-medium text-alert`, no fill — AdminPage row/history errors)
// is a DIFFERENT pattern and deliberately not routed through here.

// Tone -> the one class PAIR that tone may use. No border: the tint IS the separation (no-box
// rule), so a banner is a fill on the page, never an outlined card.
//
// Both classes are theme-aware tokens, so there is no [data-theme="dark"] re-tint to keep in sync —
// that whole mechanism is gone. A new tone therefore needs a token added in index.css, not a rule.
// Tone -> a tint fill plus its own text colour. The tints are the base colour at low alpha, so a
// banner can never drift from the status colour it belongs to.
//
// error and warning deliberately share a fill: the palette has three colours and none of them means
// "warning". Distinguishing them would need a fourth (amber), which is a new MEANING, not a new
// style. If that is ever wanted, move `warning` to bg-accent-tint rather than adding a colour.
const TONES = {
  error: "bg-alert-tint text-alert",
  warning: "bg-alert-tint text-alert",
  success: "bg-success-tint text-success",
};

// Two sizes, not a spectrum: `default` is a page-level alert, `compact` an inline note that has to
// stay subordinate to the content around it (batch-import counts, the progression hint).
const SIZES = {
  default: "px-4 py-3 text-sm",
  compact: "px-3 py-2 text-xs",
};

// Layout (margins, width, alignment) stays with the caller via className — it's context, not tone.
// Unknown props spread through because several callers pass data-cy / role / aria-live that the
// Cypress suite selects on.
function AlertBanner({
  tone = "error",
  compact = false,
  icon = null,
  className = "",
  children,
  ...rest
}) {
  const classes = [
    "rounded-lg",
    SIZES[compact ? "compact" : "default"],
    TONES[tone],
    icon ? "flex items-start gap-3" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={classes} {...rest}>
      {icon}
      {icon ? <div>{children}</div> : children}
    </div>
  );
}

export default AlertBanner;
