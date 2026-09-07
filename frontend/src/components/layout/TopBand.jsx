import logo from "../../assets/MSRIT.png";

// The full-bleed navy band across the top of every page, admin and public alike. One definition,
// because the two used to be different shapes (a rounded navy card on the public pages, a band on
// the admin ones) and that difference was visible when moving between them.
//
// FULL-BLEED IS LOAD-BEARING, not a style choice: the logo PNG is a white-knockout asset — its
// "Ramaiah Institute of Technology" wordmark is baked in as white on transparent — so it only reads
// on a dark ground. A floating card would work too, but the band is what the design settled on and
// either way the logo may never sit on a light surface.
//
// `children` is the left group: the sidebar's hamburger on admin pages, the Dark Mode toggle plus
// any page action on the public ones. The logo stays right in both.
function TopBand({ children = null }) {
  return (
    <div className="flex items-center justify-between gap-3 bg-secondary px-4 py-3 sm:px-6">
      {/* Always rendered, even when empty: it is what holds the logo against the right edge. */}
      <div className="flex flex-wrap items-center gap-2">{children}</div>
      <img src={logo} alt="Ramaiah Institute of Technology" className="h-10 w-auto" />
    </div>
  );
}

export default TopBand;
