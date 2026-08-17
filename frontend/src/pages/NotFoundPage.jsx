import { FileQuestion } from "lucide-react";
import { Link } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";

// Catch-all for any unmatched URL. Without this React Router rendered NOTHING — a blank page with
// no message and no way back, which is what a stale bookmark to a removed route (e.g. the old
// /admin/add-subject) landed on. Offers all three entry points rather than guessing which
// audience typed the bad URL.
function NotFoundPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-1 px-4 py-10 text-ink sm:px-6 lg:px-8">
      <div className="w-full max-w-md rounded-3xl border border-stroke bg-surface-1 p-6 shadow-soft sm:p-8">
        <BrandHeader className="mb-4" />

        <p className="mb-2 inline-flex items-center gap-1.5 rounded-full border border-primary/30 bg-surface-muted px-3 py-1 text-xs font-semibold uppercase tracking-[0.12em] text-primary-ink">
          <FileQuestion size={12} /> Page not found
        </p>
        <h1 className="text-3xl font-semibold text-secondary-ink">
          This page doesn't exist
        </h1>
        <p className="mt-2 text-sm text-ink" data-cy="not-found-message">
          The link may be out of date, or the address may have a typo. Pick where you'd like to go:
        </p>

        <div className="mt-6 flex flex-col gap-2">
          <MagneticCta as={Link} to="/" className="w-full rounded-xl" data-cy="not-found-home">
            Go to home
          </MagneticCta>
          <Link
            to="/student/login"
            className="inline-flex items-center justify-center rounded-xl border border-stroke bg-surface-muted px-5 py-3 text-sm font-semibold text-ink transition-colors hover:border-primary"
            data-cy="not-found-student"
          >
            Student login
          </Link>
          <Link
            to="/admin/login"
            className="inline-flex items-center justify-center rounded-xl border border-stroke bg-surface-muted px-5 py-3 text-sm font-semibold text-ink transition-colors hover:border-primary"
            data-cy="not-found-admin"
          >
            Staff login
          </Link>
        </div>
      </div>
    </div>
  );
}

export default NotFoundPage;
