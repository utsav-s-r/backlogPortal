// The label pill UNDER BrandHeader's wordmark (department name, "Manage Departments"). Static —
// never a link or button; HeaderPill is the interactive sibling in the header's action row.
// White-on-navy is FIXED, not theme-aware: it only renders inside BrandHeader's `bg-secondary` box,
// navy in both themes, so these classes need no [data-theme="dark"] counterpart.
// Margin stays with the caller, as in BrandHeader.
function HeaderBadge({ className = "", children }) {
  const classes = [
    "inline-flex rounded-full border border-white/25 bg-white/10 px-3 py-1",
    "text-[11px] font-semibold uppercase tracking-[0.12em] text-white",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return <p className={classes}>{children}</p>;
}

export default HeaderBadge;
