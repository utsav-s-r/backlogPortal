import logo from "../../assets/MSRIT.png";

// The full-bleed navy band across the top of every page, admin and public alike. One definition,
// because the two used to be different shapes (a rounded navy card on the public pages, a band on
// the admin ones) and that difference was visible when moving between them.
//
// THE BAND HAS NO CHROME COLOUR: it is the page (`bg-surface-1`) with a `border-b` under it. The
// crest's red shield reads on that ground in both themes (4.45 light, 3.48 dark), so a coloured
// slab behind it was only adding weight.
//
// ⚠ The logo PNG still carries a WHITE-KNOCKOUT wordmark, which needs a dark ground. On this light
// band the shield shows and the wordmark does not. The asset must be re-exported with dark text
// (owner said they would handle the logo); until then, treat the wordmark as missing, not broken.
//
// `children` is the left group: the sidebar's hamburger on admin pages, the Dark Mode toggle plus
// any page action on the public ones. The logo stays right in both.
function TopBand({ children = null }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-stroke bg-surface-1 px-4 py-3 sm:px-6">
      {/* Always rendered, even when empty: it is what holds the logo against the right edge. */}
      <div className="flex flex-wrap items-center gap-2">{children}</div>
      <img src={logo} alt="Ramaiah Institute of Technology" className="h-10 w-auto" />
    </div>
  );
}

export default TopBand;
