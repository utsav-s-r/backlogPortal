import logoSvg from "../../assets/MSRIT.svg?raw";

// The full-bleed band across the top of every page, admin and public alike. ONE definition: two
// shapes for the two surfaces is visible the moment you move between them.
//
// THE BAND HAS NO CHROME COLOUR: it is the page (`bg-surface-1`) with a `border-b` under it. The
// crest's red shield reads on that ground in both themes (4.45 light, 3.48 dark), so a coloured
// slab behind it was only adding weight.
//
// The logo is INLINED, not an <img>: its wordmark is `fill="currentColor"`, which only inherits
// from the page when the SVG is in the DOM. `text-wordmark` sets it per theme. The SVG carries its
// own role="img" + <title>, so the wrapper adds no label.
//
// `children` is the left group: the sidebar's hamburger on admin pages, the Dark Mode toggle plus
// any page action on the public ones. The logo stays right in both.
function TopBand({ children = null }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-stroke bg-surface-1 px-4 py-3 sm:px-6">
      {/* Always rendered, even when empty: it is what holds the logo against the right edge. */}
      <div className="flex flex-wrap items-center gap-2">{children}</div>
      <span
        className="flex text-wordmark [&>svg]:h-10 [&>svg]:w-auto"
        // Static build-time asset, not user input.
        dangerouslySetInnerHTML={{ __html: logoSvg }}
      />
    </div>
  );
}

export default TopBand;
