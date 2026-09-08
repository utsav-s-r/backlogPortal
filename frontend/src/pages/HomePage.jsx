import TopBand from "../components/layout/TopBand";
import ThemeToggle from "../components/ui/ThemeToggle";
import HeroSection from "../components/sections/HeroSection";
import ProcessSection from "../components/sections/ProcessSection";
import SkipLink from "../components/ui/SkipLink";

function HomePage() {
  return (
    // bg-surface-1 like every other page, with no backdrop layer of its own: a decorative one
    // (corner blobs, a brand gradient) is the homepage disagreeing with the rest of the app.
    <div className="min-h-screen bg-surface-1 text-ink">
      <SkipLink href="#main-content">Skip to main content</SkipLink>
      {/* TopBand directly, not PageLayout: the hero and process sections are full-bleed and own
          their own padding, so the layout's centred max-w container would box them in. */}
      <TopBand>
        <ThemeToggle />
      </TopBand>

      <main id="main-content">
        <HeroSection />
        <ProcessSection />
      </main>

      <footer className="border-t border-stroke px-4 py-8 sm:px-6 lg:px-8">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 text-sm text-ink">
          <p className="font-bold">Ramaiah Institute of Technology</p>
        </div>
      </footer>
    </div>
  );
}

export default HomePage;
