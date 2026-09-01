import { Search, X } from "lucide-react";
import AlertBanner from "../../components/AlertBanner";
import MagneticCta from "../../components/ui/MagneticCta";
import Field from "../../components/ui/Field";
import { FIELD_CONTROL } from "../../lib/formClasses";
// ALL_SEMESTERS, the canonical 1..8 subject/filter range from lib/semesters.js — NOT
// CURRENT_SEMESTERS. A filter READS existing data, and a sem-2 student's backlogs are in
// sem 1, so narrowing this to even would hide half of every student's registrations.
import { ALL_SEMESTERS } from "../../lib/semesters";
import DepartmentOptions from "../../components/ui/DepartmentOptions";

// The dashboard's filter panel. Purely presentational — every value and setter comes from
// useRegistrationFilters, which owns the draft/applied split.
//
// Two things here are part of the test surface and must not be renamed:
//   - the five ids (cycle-filter, dept-filter, semester-filter, subject-filter, search-filter).
//     admin-filter-race.cy.js reaches past data-cy and types into `#search-filter` directly.
//   - "All Subjects" stays an <option>. proctor.cy.js asserts `cy.contains("a", "Subjects")` does
//     NOT match, with a comment noting the dashboard legitimately contains "Subjects" elsewhere —
//     that only holds while this is not an anchor.
//
// `canFilterByDepartment` and `isProctor` gate whole controls rather than disabling them: a
// dept-pinned role cannot widen scope (the server ignores the param), and a proctor sees a few
// dozen students in one department, where subject/semester/dept narrowing is noise.
function FilterPanel({
  isProctor,
  canFilterByDepartment,
  filtersDirty,
  applyFilters,
  clearFilters,
  allSubjects,
  loadingSubjects,
  subjectFilter,
  setSubjectFilter,
  semesterFilter,
  setSemesterFilter,
  deptFilter,
  setDeptFilter,
  departments,
  searchInput,
  setSearchInput,
  examCycles,
  cycleFilter,
  setCycleFilter,
  cyclesError,
  subjectsError,
  departmentsError,
}) {
  // dropdown groups — the type distinction lives in the option list, not in a second control
  const regularSubjects = allSubjects.filter((s) => s.subjectType === "REGULAR");
  const electiveSubjects = allSubjects.filter((s) => s.subjectType === "ELECTIVE");

  return (
    <section className="mb-6 rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft">
      <h3 className="mb-3 text-lg font-semibold text-secondary-ink">
        Filters
      </h3>

      {cyclesError && (
        <AlertBanner
          tone="error"
          role="alert"
          data-cy="admin-cycles-error"
          className="mb-3"
        >
          {cyclesError}
        </AlertBanner>
      )}
      {/* Amber, not red: the table still works, only this dropdown's options are unknown. */}
      {(subjectsError || departmentsError) && (
        <AlertBanner
          tone="warning"
          role="alert"
          data-cy="admin-filter-options-error"
          className="mb-3"
        >
          {[subjectsError, departmentsError].filter(Boolean).join(" ")}
        </AlertBanner>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
        {/* Exam Cycle Filter */}
        <Field label="Exam Cycle" htmlFor="cycle-filter">
          <select
            id="cycle-filter"
            value={cycleFilter}
            onChange={(e) => setCycleFilter(e.target.value)}
            className={FIELD_CONTROL}
          >
            <option value="">All Cycles</option>
            {examCycles.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
                {c.active ? " (active)" : ""}
              </option>
            ))}
          </select>
        </Field>

        {/* Department Filter — ADMIN/PRINCIPAL only; every other role is pinned server-side */}
        {canFilterByDepartment && (
          <Field label="Department" htmlFor="dept-filter">
            <select
              id="dept-filter"
              value={deptFilter}
              onChange={(e) => setDeptFilter(e.target.value)}
              className={FIELD_CONTROL}
            >
              <option value="">All Departments</option>
              <DepartmentOptions departments={departments} />
            </select>
          </Field>
        )}

        {/* Semester Filter */}
        {!isProctor && (
          <Field label="Semester" htmlFor="semester-filter">
            <select
              id="semester-filter"
              value={semesterFilter}
              onChange={(e) => setSemesterFilter(e.target.value)}
              className={FIELD_CONTROL}
            >
              <option value="">All Semesters</option>
              {ALL_SEMESTERS.map((s) => (
                <option key={s} value={s}>
                  Semester {s}
                </option>
              ))}
            </select>
          </Field>
        )}

        {/* Subject Filter — carries the type distinction, so there is no separate type control */}
        {!isProctor && (
          <Field label="Subject" htmlFor="subject-filter">
            <select
              id="subject-filter"
              value={subjectFilter}
              onChange={(e) => setSubjectFilter(e.target.value)}
              className={FIELD_CONTROL}
              disabled={loadingSubjects}
            >
              <option value="">
                {loadingSubjects ? "Loading..." : "All Subjects"}
              </option>
              <option value="type:REGULAR">All Regular subjects</option>
              <option value="type:ELECTIVE">All Elective subjects</option>
              {regularSubjects.length > 0 && (
                <optgroup label="Regular">
                  {regularSubjects.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.subjectName} ({s.courseCode})
                    </option>
                  ))}
                </optgroup>
              )}
              {electiveSubjects.length > 0 && (
                <optgroup label="Elective">
                  {electiveSubjects.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.subjectName} ({s.courseCode})
                    </option>
                  ))}
                </optgroup>
              )}
            </select>
          </Field>
        )}

        {/* Search Filter */}
        <Field label="Search by USN / Name" htmlFor="search-filter">
          <input
            id="search-filter"
            type="text"
            placeholder="Enter USN or name..."
            data-cy="admin-search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            className={FIELD_CONTROL}
          />
        </Field>
      </div>

      <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-stroke pt-4">
        <MagneticCta
          type="button"
          onClick={applyFilters}
          className="gap-2 rounded-xl"
          data-cy="admin-filters-apply"
        >
          <Search size={15} /> Apply filters
        </MagneticCta>
        <button
          type="button"
          onClick={clearFilters}
          data-cy="admin-filters-clear"
          className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary"
        >
          <X size={15} /> Clear all filters
        </button>
        {filtersDirty && (
          <span
            className="text-xs font-semibold text-amber-600"
            data-cy="admin-filters-dirty"
          >
            Unapplied changes — click Apply
          </span>
        )}
      </div>
    </section>
  );
}

export default FilterPanel;
