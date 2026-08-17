import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import BrandIdentity from "./BrandIdentity";
import ThemeToggle from "../ui/ThemeToggle";

export default function StickyNav() {
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    function onScroll() {
      setScrolled(window.scrollY > 40);
    }

    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <header
      className={`sticky top-0 z-50 border-b border-white/10 bg-secondary/95 backdrop-blur transition-all duration-300 ${
        scrolled ? "py-2" : "py-3"
      }`}
    >
      <div className="mx-auto flex w-full max-w-7xl items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
        <Link to="/" aria-label="Go to homepage">
          {/* The wordmark collides with the theme toggle on phones,
              so it is hidden below sm (the logo image already carries the branding). */}
          <BrandIdentity compact={scrolled} wordmarkClassName="hidden sm:block" />
        </Link>

        <ThemeToggle />
      </div>
    </header>
  );
}
