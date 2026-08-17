// The one status banner. Replaced 32 hand-rolled copies (2026-08-16) whose only real variance was
// spelling: dark mode already collapsed them via the index.css re-tints (both text-red-600 and
// text-red-700 -> #f87171, all four amber text shades -> #fbbf24), so the sprawl was visible in
// LIGHT only. Each hand-typed copy was a place a class without a dark re-tint could slip in and
// paint a bright patch on the dark page, with no build, lint or test failure — Cypress asserts
// nothing about styles.
//
// Bare inline error text (`text-sm font-medium text-red-600`, no fill — AdminPage row/history
// errors) is a DIFFERENT pattern and deliberately not routed through here.

// Tone -> the one class triple that tone may use. Every class has a matching [data-theme="dark"]
// re-tint in index.css (:229-273); that block is what keeps these light-authored tints legible on
// the dark page. A new tone needs its re-tints added there in the same edit, or it ships as a
// bright patch in dark. Shades are the darkest with a re-tint: dark is unchanged by construction
// (it collapses them anyway) and light gains contrast (red-700 on red-50 is 6.9:1, red-600 4.8:1).
const TONES = {
  error: "border-red-200 bg-red-50 text-red-700",
  warning: "border-amber-200 bg-amber-50 text-amber-800",
  success: "border-green-200 bg-green-50 text-green-800",
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
    "rounded-xl border",
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
