import StickyNav from "../components/layout/StickyNav";
import HeroSection from "../components/sections/HeroSection";
import ProcessSection from "../components/sections/ProcessSection";

function HomePage() {
  return (
    // bg-surface-1 like every other page — the homepage used to carry its own fixed backdrop layer
    // (decorative corner blobs in light, a brand gradient in dark). Both are gone: the page now
    // sits on the same flat surface as the rest of the app.
    <div className="min-h-screen bg-surface-1 text-ink">
      <a
        href="#main-content"
        className="sr-only left-4 top-4 z-[60] rounded-md bg-cta px-4 py-2 text-sm font-semibold text-cta-text focus:not-sr-only focus:fixed"
      >
        Skip to main content
      </a>
      <StickyNav />

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
