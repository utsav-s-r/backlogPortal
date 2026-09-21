import { FileQuestion } from "lucide-react";
import { Link } from "react-router-dom";
import PageLayout from "../components/layout/PageLayout";
import PrimaryCta from "../components/ui/PrimaryCta";
import { btn } from "../lib/buttonClasses";

// Catch-all for any unmatched URL. Without it React Router renders NOTHING — a blank page with no
// message and no way back, which is where a stale bookmark to a removed route lands. Offers all
// three entry points rather than guessing which audience typed the bad URL.
function NotFoundPage() {
  return (
    <PageLayout containerClassName="max-w-md">
      <div className="py-6 sm:py-8">
        <p className="mb-2 inline-flex items-center gap-1.5 rounded-lg bg-accent-tint px-3 py-1 text-xs font-semibold uppercase tracking-[0.12em] text-accent">
          <FileQuestion size={12} /> Page not found
        </p>
        <h1 className="text-3xl font-semibold text-secondary-ink">
          This page doesn't exist
        </h1>
        <p className="mt-2 text-sm text-ink" data-cy="not-found-message">
          The link may be out of date, or the address may have a typo. Pick where you'd like to go:
        </p>

        <div className="mt-6 flex flex-col gap-2">
          <PrimaryCta as={Link} to="/" size="lg" className="w-full" data-cy="not-found-home">
            Go to home
          </PrimaryCta>
          <Link
            to="/student/login"
            className={btn("neutral", "lg")}
            data-cy="not-found-student"
          >
            Student login
          </Link>
          <Link
            to="/admin/login"
            className={btn("neutral", "lg")}
            data-cy="not-found-admin"
          >
            Staff login
          </Link>
        </div>
      </div>
    </PageLayout>
  );
}

export default NotFoundPage;
