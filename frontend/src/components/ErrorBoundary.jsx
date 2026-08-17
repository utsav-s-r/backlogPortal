import { Component } from "react";

// The app's last line of defence. React only supports error boundaries as CLASS components — there
// is no hook equivalent — and react-error-boundary is deliberately not a dependency, so this is
// the one class in the codebase.
//
// SCOPE, precisely: this catches throws during RENDER and lifecycle, plus failed lazy-chunk loads
// (a dropped network mid-navigation). It does NOT catch errors in event handlers, in promise
// callbacks, or in async code — React never sees those. Fetch failures therefore still need their
// own handling at each call site; this is not a substitute for that. Before it existed, a single
// render throw (e.g. mapping over a field the server didn't send) blanked the entire page.
class ErrorBoundary extends Component {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error, info) {
    // No error-reporting service is wired up; the console is the only sink there is.
    console.error("Unhandled render error", error, info?.componentStack);
  }

  render() {
    if (!this.state.hasError) return this.props.children;

    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-1 px-4 py-10 text-ink sm:px-6 lg:px-8">
        <div
          className="w-full max-w-md rounded-3xl border border-stroke bg-surface-1 p-6 shadow-soft sm:p-8"
          role="alert"
          data-cy="error-boundary"
        >
          <h1 className="text-2xl font-semibold text-secondary-ink">Something went wrong</h1>
          <p className="mt-2 text-sm text-ink">
            This page failed to load. Reloading usually fixes it — if it keeps happening, contact
            the department office.
          </p>
          <div className="mt-6 flex flex-col gap-2">
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="inline-flex items-center justify-center rounded-xl bg-cta px-5 py-3 text-sm font-semibold text-cta-text"
              data-cy="error-boundary-reload"
            >
              Reload page
            </button>
            {/* A plain anchor, not <Link>: the router may be the thing that just threw. */}
            <a
              href="/"
              className="inline-flex items-center justify-center rounded-xl border border-stroke bg-surface-muted px-5 py-3 text-sm font-semibold text-ink transition-colors hover:border-primary"
              data-cy="error-boundary-home"
            >
              Go to home
            </a>
          </div>
        </div>
      </div>
    );
  }
}

export default ErrorBoundary;
