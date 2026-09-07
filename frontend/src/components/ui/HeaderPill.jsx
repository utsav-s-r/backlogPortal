import { btn } from "../../lib/buttonClasses";
// A pill button/link inside a navy top band. ONE style, and only one: a white fill on the navy,
// a stronger white fill on hover. No border — a control is a fill, never an outline (no-box rule),
// and its hover state is a different fill, never the appearance of an edge.
//
// The `outline`/`filled` variant pair is gone: the only callers that asked for `filled` were
// AdminPage's six section pills, which the sidebar replaced.
//
// White-on-navy is fixed, not theme-aware: this only ever renders inside a `bg-secondary` band,
// which is navy in both themes. That is why there are no `[data-theme="dark"]` counterparts to
// these classes and why none are needed.
//
// Renders a <button> by default; pass `as={Link}` to navigate, the same escape hatch PrimaryCta
// uses. Focus styling is deliberately absent — the global :focus-visible outline in index.css
// already covers a/button.
const BASE = btn("onNavy");

function HeaderPill({ children, className = "", as: Component = "button", ...props }) {
  return (
    <Component
      className={[BASE, className].filter(Boolean).join(" ")}
      {...(Component === "button" ? { type: "button" } : {})}
      {...props}
    >
      {children}
    </Component>
  );
}

export default HeaderPill;
