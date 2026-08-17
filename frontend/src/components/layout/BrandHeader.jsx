import BrandIdentity from "./BrandIdentity";

// The navy brand box. Replaced 12 hand-typed copies (2026-08-16) in two shapes: a plain brand card
// on the login/404 pages, and a page header with actions everywhere else. They were one box all
// along — `justify-between` is inert on a single child, so the card is just this with no actions.
//
// The giveaway that they were typed independently: 6 spelled the padding `p-4` and 3 spelled it
// `px-4 py-4`, which render identically. The 3 card sites used `px-4 py-3` and now match the rest.
//
// `badge` renders under the wordmark (a dept pill, a "Manage Departments" label). It is a prop
// rather than part of children because it belongs to the LEFT group; children are the right-hand
// actions that `justify-between` pushes away. The wrapping div only appears when a badge exists,
// so the no-badge sites keep their original DOM exactly.
//
// Margin stays with the caller via className — it is layout, not identity (mb-6 on page headers,
// mb-4 on cards, none on RegistrationPage's confirmation view).
function BrandHeader({ className = "", badge = null, children = null }) {
  const classes = [
    "flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-stroke",
    "bg-secondary p-4 text-white shadow-soft sm:px-6",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <header className={classes}>
      {badge ? (
        <div>
          <BrandIdentity compact />
          {badge}
        </div>
      ) : (
        <BrandIdentity compact />
      )}
      {children}
    </header>
  );
}

export default BrandHeader;
