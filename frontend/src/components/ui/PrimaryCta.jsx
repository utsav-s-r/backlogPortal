// Primary call-to-action. Renders a <button> by default; pass `as={Link}` (or any component) to
// navigate instead. Hover/press feedback is CSS only — this project uses no motion library.
//
// Focus styling is deliberately absent: the global :focus-visible outline in index.css already
// covers a/button/input/select/textarea. Don't add a local ring — a ring in var(--color-cta) is the
// button's OWN fill, visible only in the ring-offset gap.
const BASE = [
  "inline-flex items-center justify-center rounded-full px-5 py-3",
  "bg-cta text-cta-text text-sm font-semibold",
  "shadow-soft transition-[box-shadow,transform] duration-200",
  "hover:shadow-[0_10px_30px_var(--color-cta-glow)]",
  // press feedback
  "active:scale-[0.98]",
].join(" ");

export default function PrimaryCta({
  children,
  className,
  as: Component = "button",
  type = "button",
  ...props
}) {
  return (
    <Component
      className={[BASE, className].filter(Boolean).join(" ")}
      {...(Component === "button" ? { type } : {})}
      {...props}
    >
      {children}
    </Component>
  );
}
