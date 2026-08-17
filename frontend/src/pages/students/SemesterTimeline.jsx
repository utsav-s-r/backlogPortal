import { useState } from "react";
import { formatAcademicYear } from "../../lib/academicYear";

// Editable "which academic year did the student study each semester" table. Its one consumer is
// the Semesters panel on the Manage-students tab (it was shared with a Progression tab until that
// was deleted 2026-08-16). Presentational: the parent loads the student
// (name/currentSemester/entrySemester/terms) and owns the save callback, which PUTs the
// per-semester override. See docs/adr/backlog-progression.md.

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

// One row per semester, entry through 8, all editable. A recorded year is prefilled; semesters
// with no row arrive as academicYear === null (blank, editable, shown as a dash). Rows past
// currentSemester are flagged `future` and render muted — not yet reached, but still settable,
// e.g. to correct a year-back.
function buildTimelineRows(student) {
  const floor = Math.max(1, student.entrySemester || 1);
  const bySem = new Map((student.terms || []).map((t) => [t.semester, t.academicYear]));
  const rows = [];
  for (let s = floor; s <= 8; s++) {
    rows.push({
      semester: s,
      academicYear: bySem.has(s) ? bySem.get(s) : null,
      future: s > student.currentSemester,
    });
  }
  return rows;
}

// The full timeline table. `onSaveYear(semester, yearString)` fires on a row's Save; the parent
// parses, persists, and feeds back a refreshed student.
export function SemesterTimeline({ student, onSaveYear, busy }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-stroke">
      <table className="w-full text-left text-sm">
        <thead className="bg-surface-muted text-xs uppercase tracking-[0.08em] text-ink-muted">
          <tr>
            <th className="px-3 py-2">Semester</th>
            <th className="px-3 py-2">Academic year</th>
            <th className="px-3 py-2"></th>
          </tr>
        </thead>
        <tbody>
          {buildTimelineRows(student).map((r) => (
            <TermRow
              key={`${r.semester}-${r.academicYear ?? "none"}`}
              semester={r.semester}
              academicYear={r.academicYear}
              future={r.future}
              onSave={onSaveYear}
              busy={busy}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TermRow({ semester, academicYear, future, onSave, busy }) {
  const isMissing = academicYear == null;
  const original = isMissing ? "" : formatAcademicYear(academicYear);
  const [year, setYear] = useState(original);
  return (
    <tr className="border-t border-stroke">
      <td className={`px-3 py-2 ${future ? "text-ink-muted" : ""}`}>
        Semester {semester}
        {future && (
          <span className="ml-2 text-xs font-normal text-ink-muted">(not yet reached)</span>
        )}
      </td>
      <td className="px-3 py-2">
        <div className="flex items-center gap-2">
          {isMissing && (
            <span className="text-ink-muted" aria-hidden="true" data-cy={`prog-term-dash-${semester}`}>
              —
            </span>
          )}
          <input
            className={`${inputClass} w-32`}
            type="text"
            placeholder="e.g. 2024-25"
            value={year}
            onChange={(e) => setYear(e.target.value)}
            data-cy={`prog-term-year-${semester}`}
          />
          {isMissing && (
            <span className="text-xs font-semibold text-amber-700" data-cy={`prog-term-missing-${semester}`}>
              not set
            </span>
          )}
        </div>
      </td>
      <td className="px-3 py-2">
        <button
          type="button"
          onClick={() => onSave(semester, year)}
          disabled={busy || !year.trim() || year === original}
          data-cy={`prog-term-save-${semester}`}
          className="rounded-md border border-stroke bg-surface-1 px-3 py-1 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-50"
        >
          Save
        </button>
      </td>
    </tr>
  );
}
