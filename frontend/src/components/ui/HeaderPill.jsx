// A pill button/link inside the navy BrandHeader bar. Two variants, and only two: `filled`
// (`bg-white/20 … hover:bg-white/30`, the dashboard's section nav) and `outline`
// (`hover:bg-white/10`, everything else). Don't hand-type a third spelling.
//
// White-on-navy is fixed, not theme-aware: this only ever renders inside BrandHeader's
// `bg-secondary` box, which is navy in both themes. That is why there are no `[data-theme="dark"]`
// counterparts to these classes and why none are needed.
//
// Renders a <button> by default; pass `as={Link}` to navigate, the same escape hatch PrimaryCta
// uses. Focus styling is deliberately absent — the global :focus-visible outline in index.css
// already covers a/button.
const BASE =
  "inline-flex items-center gap-1 rounded-full border border-white/30 px-4 py-2 " +
  "text-sm font-semibold text-white transition-colors";

const VARIANTS = {
  outline: "hover:bg-white/10",
  filled: "bg-white/20 hover:bg-white/30",
};

function HeaderPill({
  children,
  className = "",
  variant = "outline",
  as: Component = "button",
  ...props
}) {
  return (
    <Component
      className={[BASE, VARIANTS[variant], className].filter(Boolean).join(" ")}
      {...(Component === "button" ? { type: "button" } : {})}
      {...props}
    >
      {children}
    </Component>
  );
}

export default HeaderPill;
