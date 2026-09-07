import { btn } from "../../lib/buttonClasses";
// A pill button/link inside a navy top band. ONE style, and only one: a white fill on the navy,
// a stronger white fill on hover. No border — a control is a fill, never an outline (no-box rule),
// and its hover state is a different fill, never the appearance of an edge.
//
// The `outline`/`filled` variant pair is gone: the only callers that asked for `filled` were
// AdminPage's six section pills, which the sidebar replaced.
//
// Uses the `quiet` tone: no fill until hovered. It renders inside the top band and the rail, which
// are now the page and one step up from it rather than a navy slab — a filled pill there would
// compete with the page's own primary action.
//
// Renders a <button> by default; pass `as={Link}` to navigate, the same escape hatch PrimaryCta
// uses. Focus styling is deliberately absent — the global :focus-visible outline in index.css
// already covers a/button.
const BASE = btn("quiet");

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
