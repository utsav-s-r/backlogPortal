// Skip-to-content link: `sr-only` until focused, then `focus:not-sr-only focus:fixed` pins it
// top-left. `href` and label differ per page; the shell does not.
function SkipLink({ href, children }) {
  return (
    <a
      href={href}
      className="sr-only left-4 top-4 z-[60] rounded-md bg-cta px-4 py-2 text-sm font-semibold text-cta-text focus:not-sr-only focus:fixed"
    >
      {children}
    </a>
  );
}

export default SkipLink;
