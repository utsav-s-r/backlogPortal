// Primary call-to-action. Renders a <button> by default; pass `as={Link}` (or any component) to
// navigate instead. The name is vestigial: there is no magnetic hover effect, and this project uses
// no motion library — don't add one back to make the name true.
//
// Focus styling is deliberately absent: the global :focus-visible outline in index.css already
// covers a/button/input/select/textarea. This component used to set focus-visible:outline-none and
// re-implement it as a ring coloured var(--color-cta) — the button's OWN fill, so the ring was
// invisible except for the ring-offset gap. Don't reintroduce a local ring here.
const BASE = [
  "inline-flex items-center justify-center rounded-full px-5 py-3",
  "bg-cta text-cta-text text-sm font-semibold",
  "shadow-soft transition-[box-shadow,transform] duration-200",
  "hover:shadow-[0_10px_30px_var(--color-cta-glow)]",
  // press feedback
  "active:scale-[0.98]",
].join(" ");

export default function MagneticCta({
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
