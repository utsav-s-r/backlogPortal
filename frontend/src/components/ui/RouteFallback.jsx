// What Suspense shows while a route chunk is in flight (App.jsx). It used to be an empty
// min-h-screen div, on the assumption that chunks arrive too fast to be worth a spinner. That holds
// on warm desktop wifi and fails on a phone: the screen went blank on tap, so the link read as a
// dead button rather than as a page loading.
//
// The `route-fallback` class holds it invisible for 300ms before fading in, which keeps the original
// no-flash property — a fast load still shows nothing at all. min-h-screen keeps the layout from
// collapsing mid-swap.
//
// The spinner is an INLINE SVG, not lucide's LoaderCircle: this renders from the ENTRY chunk, so a
// lucide import makes createLucideIcon (~19kB gzip) a static entry dependency that Vite
// modulepreloads on every cold load — weight on the exact critical path this component exists to
// cover. Nothing else in the app should copy this; a normal page importing lucide is already paying
// for a chunk it loads anyway.
//
// Text, not just the spinner: prefers-reduced-motion freezes the spin (index.css forces
// animation-duration), so the words are what carry the meaning for those users and for screen
// readers.
export default function RouteFallback() {
  return (
    <div
      className="route-fallback flex min-h-screen items-center justify-center gap-2 text-sm text-secondary-ink"
      role="status"
      aria-live="polite"
      data-cy="route-fallback"
    >
      <svg
        className="animate-spin"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        aria-hidden="true"
      >
        <path d="M21 12a9 9 0 1 1-6.219-8.56" />
      </svg>
      Loading…
    </div>
  );
}
