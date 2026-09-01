import { ArrowLeft } from "lucide-react";
import { Link } from "react-router-dom";
import BrandHeader from "./BrandHeader";
import HeaderPill from "../ui/HeaderPill";

// The chrome every non-tabbed admin section shares: tinted full-height page, centred container, navy
// header with a "Dashboard" pill back to /admin. One definition so the pill cannot drift again —
// size and accessible name were both inconsistent across the copies this replaced. "Dashboard" stays
// the visible text, so the aria-label still contains it and Label in Name (WCAG 2.5.3) holds.
//
// AdminPage is deliberately NOT a caller: skip link first, max-w-7xl, and filter/table specs that
// assert raw ids and parent-child structure.
const dashboardPill = (
  <HeaderPill as={Link} to="/admin" aria-label="Back to admin dashboard">
    <ArrowLeft size={14} /> Dashboard
  </HeaderPill>
);

function AdminPageShell({
  // Passed, not derived: the 5xl pages carry `pb-8` and the 3xl ones don't. Incidental, but
  // normalising it would be an unrequested 2rem spacing change.
  containerClassName,
  badge = null,
  // Extra pills, rendered BEFORE the Dashboard pill. Without them the pill stays BrandHeader's
  // direct child, keeping the single-pill sites' DOM unchanged.
  actions = null,
  children,
}) {
  return (
    <div className="min-h-screen bg-surface-1 px-4 py-8 text-ink sm:px-6 lg:px-8">
      <div className={`mx-auto w-full ${containerClassName}`}>
        <BrandHeader className="mb-6" badge={badge}>
          {actions ? (
            <div className="flex gap-2">
              {actions}
              {dashboardPill}
            </div>
          ) : (
            dashboardPill
          )}
        </BrandHeader>

        {children}
      </div>
    </div>
  );
}

export default AdminPageShell;
