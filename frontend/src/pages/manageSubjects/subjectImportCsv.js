// CSV shape for the subject importer: column order, the header line, and the one multi-value cell.
// Parsing lives in lib/csv.js; this module only maps cells to request fields. Own module, not a
// helper inside the tab: react-refresh/only-export-components bars a non-component export there.

import { dropHeaderRow, parseCsv } from "../../lib/csv";

export const SUBJECT_CSV_HEADER =
  "courseCode,subjectName,semester,credits,subjectType,eligibleDeptCodes";

// The elective row quotes a name containing a comma on purpose — the template doubles as the
// worked example of both escapes an admin will hit.
export const SUBJECT_CSV_TEMPLATE = [
  SUBJECT_CSV_HEADER,
  "CSL44,Data Structures,4,4,REGULAR,",
  'CSL55,"Design, Analysis of Algorithms",5,3,ELECTIVE,CS|CV',
].join("\n");

// Eligible departments are the one non-scalar field, so they share a cell separated by "|" — a
// comma there would be indistinguishable from the column separator. They are department CODES
// ("CS"), never ids: ids are @GeneratedValue and differ between the dev and production databases,
// so a file authored against one would import silently wrong against the other.
const DEPT_CODE_SEPARATOR = "|";

const HEADER_FIRST_CELLS = ["coursecode", "code"];

/**
 * Parse the pasted CSV into subject import rows.
 *
 * There is no academicYear column by design: the year is batch-level, forced server-side per row,
 * mirroring SubjectCloneService's targetYear. It is the year-binding key and year-binding is
 * fail-closed, so a mistyped year yields a subject that looks correct in the catalog and is
 * invisible to every student who needs it — typing it once, next to the Preview button, is the
 * whole safeguard.
 *
 * Blank numeric cells become null rather than 0, so the server reports "semester is required"
 * instead of silently filing the subject under semester 0.
 *
 * @throws {Error} from parseCsv on malformed quoting
 * @returns {Array<object>} rows in SubjectImportRequest.Row shape
 */
export function parseSubjectCsv(text) {
  return dropHeaderRow(parseCsv(text), HEADER_FIRST_CELLS).map((cells) => {
    const [courseCode, subjectName, semester, credits, subjectType, eligibleDeptCodes] = cells;
    return {
      courseCode: courseCode || "",
      subjectName: subjectName || "",
      semester: semester ? Number(semester) : null,
      credits: credits ? Number(credits) : null,
      subjectType: subjectType || "",
      eligibleDeptCodes: (eligibleDeptCodes || "")
        .split(DEPT_CODE_SEPARATOR)
        .map((c) => c.trim())
        .filter(Boolean),
    };
  });
}
