// A label/value pair for a fact the user cannot edit here — profile identity on the student
// dashboard, the locked "Registering as" summary on the registration form. Replaced two copies
// (2026-08-31) that were the same component under two names: StudentDashboardPage's `Field` and
// RegistrationPage's `LockedField`, drifted by `gap-1.5` vs `gap-1` and a `font-medium`.
//
// A <div>, NOT the <label> that components/ui/Field uses. There is no control here to associate
// with, and a <label> pointing at nothing is exactly the broken markup Field exists to prevent.
// Sibling of Field, not a variant of it: same label typography, different job.
//
// Empty renders as an em-dash rather than collapsing, so a missing value reads as "we have no
// value" instead of the row silently disappearing from the grid.

import { FIELD_LABEL } from "../../lib/formClasses";

function ReadOnlyField({ label, value }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className={`${FIELD_LABEL} text-ink-muted`}>{label}</span>
      <span className="text-sm font-medium text-ink">{value || "—"}</span>
    </div>
  );
}

export default ReadOnlyField;
