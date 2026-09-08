import logo from "../../assets/MSRIT.png";

// The full-bleed band across the top of every page, admin and public alike. ONE definition: two
// shapes for the two surfaces is visible the moment you move between them.
//
// THE BAND HAS NO CHROME COLOUR: it is the page (`bg-surface-1`) with a `border-b` under it. The
// crest's red shield reads on that ground in both themes (4.45 light, 3.48 dark), so a coloured
// slab behind it was only adding weight.
//
// ⚠ The logo PNG carries a WHITE-KNOCKOUT wordmark, which needs a dark ground. On this light band
// the shield reads and the wordmark does not. The asset is the owner's to re-export — treat the
// wordmark as missing, not broken, and do not raise it.
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
