import { btn } from "../../lib/buttonClasses";
// Primary call-to-action. Renders a <button> by default; pass `as={Link}` (or any component) to
// navigate instead. Hover/press feedback is CSS only — this project uses no motion library.
//
// Focus styling is deliberately absent: the global :focus-visible outline in index.css already
// covers a/button/input/select/textarea. Don't add a local ring — a ring in var(--color-cta) is the
// button's OWN fill, visible only in the ring-offset gap.
// Geometry and colour come from the ONE button system, so the main action is the same shape and
// size as everything beside it — it was rounded-full while every other button was rounded-lg, which
// read as two different button families on one page. What still makes it the primary action is the
// glow and the press feedback, which nothing else has.
// SIZE MUST MATCH WHATEVER SITS BESIDE IT. This was hardcoded to "lg" while its neighbours were
// "md", so "Apply filters" rendered 44px tall next to a 36px "Clear all filters" — two buttons in
// one row at two different sizes, which is exactly what the button system exists to stop. `md` is
// the default because the common case is an inline form action; the near-empty pages (404, the
// error boundary) stack it with other `lg` buttons and pass size="lg".
const DECOR = [
  "shadow-soft transition-[box-shadow,transform] duration-200",
  "hover:shadow-[0_10px_30px_var(--color-cta-glow)]",
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
