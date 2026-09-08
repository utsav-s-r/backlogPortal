import { btn } from "../../lib/buttonClasses";
// A pill button/link inside the top band. ONE style, and only one — the `quiet` tone: no fill until
// hovered. The band and the rail are page surfaces, so a filled pill there would compete with the
// page's own primary action. No border either: a control is a fill, never an outline (no-box rule),
// and its hover is a different fill, never the appearance of an edge.
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
