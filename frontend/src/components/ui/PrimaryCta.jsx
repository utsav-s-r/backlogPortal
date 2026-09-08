import { btn } from "../../lib/buttonClasses";
// Primary call-to-action. Renders a <button> by default; pass `as={Link}` (or any component) to
// navigate instead. Hover/press feedback is CSS only — this project uses no motion library.
//
// Focus styling is deliberately absent: the global :focus-visible outline in index.css already
// covers a/button/input/select/textarea. Don't add a local ring — a ring in the accent is the
// button's OWN fill, visible only in the ring-offset gap.
//
// Geometry and colour come from the ONE button system, so the main action is the same shape and
// size as everything beside it; a different radius here reads as two button families on one page.
// What marks it as primary is `shadow-soft` plus the press feedback, which nothing else has — no
// halo, which would need a shade existing only for it.
//
// SIZE MUST MATCH WHATEVER SITS BESIDE IT: an `lg` beside an `md` is 44px next to 36px in one row,
// which is exactly what the button system exists to stop. `md` is the default because the common
// case is an inline form action; the near-empty pages (404, the error boundary) stack it with other
// `lg` buttons and pass size="lg".
const DECOR = [
  "shadow-soft transition-[box-shadow,transform] duration-200",
  // press feedback
  "active:scale-[0.98]",
].join(" ");

export default function PrimaryCta({
  children,
  className,
  size = "md",
  as: Component = "button",
  type = "button",
  ...props
}) {
  return (
    <Component
      className={[btn("accent", size), DECOR, className].filter(Boolean).join(" ")}
      {...(Component === "button" ? { type } : {})}
      {...props}
    >
      {children}
    </Component>
  );
}
