import ThemeToggle from "../ui/ThemeToggle";
import TopBand from "./TopBand";

// Shell for sidebar-less pages (student dashboard, /register, logins, 404); too few links for a
// rail. HomePage uses TopBand directly: its hero is full-bleed. Dark Mode is built in so no page
// forgets it.
// `fullHeightClassName` REPLACES min-h-screen: two min-heights resolve by CSS order, not string order.
function PageLayout({
  actions = null,
  containerClassName = "max-w-7xl",
  fullHeightClassName = "min-h-screen",
  children,
}) {
  return (
    <div className={[fullHeightClassName, "bg-surface-1 text-ink"].join(" ")}>
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
