import ThemeToggle from "../ui/ThemeToggle";
import TopBand from "./TopBand";

// The chrome for the five pages WITHOUT a sidebar — the student dashboard, /register, the two
// logins and 404. They get no rail on purpose: a student has two destinations and a visitor has
// none, so a 260px rail holding one link is worse than the band, and their actions live in the band
// instead. The homepage is NOT a caller: its hero is full-bleed, so it uses TopBand directly rather
// than be boxed in by the centred container here.
//
// The Dark Mode toggle is built in rather than passed, so no page can forget it. `actions` is
// whatever else that page needs beside it (Home + Log out on the dashboard, Back to Dashboard on
// /register).
function PageLayout({ actions = null, containerClassName = "max-w-7xl", children }) {
  return (
    <div className="min-h-screen bg-surface-1 text-ink">
      <TopBand>
        <ThemeToggle />
        {actions}
      </TopBand>
      <main className={`mx-auto w-full px-4 py-8 sm:px-6 lg:px-8 ${containerClassName}`}>
        {children}
      </main>
    </div>
  );
}

export default PageLayout;
