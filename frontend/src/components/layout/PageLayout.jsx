import ThemeToggle from "../ui/ThemeToggle";
import TopBand from "./TopBand";

// The chrome for every page WITHOUT a sidebar — the student pages, the two logins, the homepage and
// 404. They get no rail on purpose: a student has two destinations and a visitor has none, so a
// 260px rail holding one link is worse than the band, and their actions live in the band instead.
//
// The Dark Mode toggle is built in rather than passed, because it is on all six and forgetting it
// on one is exactly the kind of drift the band was consolidated to stop. `actions` is whatever else
// that page needs beside it (Home + Log out on the dashboard, Back to Dashboard on /register).
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
