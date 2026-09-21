import { useState } from "react";
import CsvImportPanel from "../../components/CsvImportPanel";
import DepartmentOptions from "../../components/ui/DepartmentOptions";
import Field from "../../components/ui/Field";
import { formatAcademicYear, recentAcademicYears } from "../../lib/academicYear";
import { FIELD_CONTROL } from "../../lib/formClasses";
import { parseSubjectCsv, SUBJECT_CSV_HEADER, SUBJECT_CSV_TEMPLATE } from "./subjectImportCsv";

// Bulk-load a year's subject catalog from CSV — the first-load path, where Clone can't help because
// there is no previous year to copy. The shell supplies departments and the dept-lock context;
// CsvImportPanel owns the chrome, the run and the result table.
//
// The academic year is chosen ONCE, not per row, and the server stamps it on every row: it is the
// year-binding key and year-binding is fail-closed, so a mistyped year yields subjects that look
// right in the catalog and are invisible to the students who need them.
function ImportSubjectsTab({ departments, deptLocked, pinnedDeptId }) {
  const [year, setYear] = useState("");
  const [deptId, setDeptId] = useState("");

  const effectiveDeptId = deptLocked ? pinnedDeptId : deptId;

  return (
    <CsvImportPanel
      title="Import subjects (CSV)"
      dataCyPrefix="subjects-import"
      headerLine={SUBJECT_CSV_HEADER}
      placeholder="CSL44,Data Structures,4,4,REGULAR,"
      templateText={SUBJECT_CSV_TEMPLATE}
      templateFilename="subjects-template.csv"
      parse={parseSubjectCsv}
      endpoint="/admin/subjects/import"
      // runs before the parse, so a missing year beats "paste at least one row" — the order
      // import-subjects.cy.js pins with an empty textarea
      validate={() => {
        if (!year) return "Choose the academic year these subjects are offered in.";
        if (!effectiveDeptId) return "Choose the department.";
        return null;
      }}
      buildPayload={(rows, dryRun) => ({
        rows,
        deptId: Number(effectiveDeptId),
        academicYearOffered: Number(year),
        dryRun,
      })}
      verb="Imported"
      idLabel="Course code"
      idKey="courseCode"
      description={
        <>
          One row per line: <code>{SUBJECT_CSV_HEADER}</code>. The academic year and department are
          set below and applied to every row, so they are not columns. Wrap a value containing a
          comma in double quotes, e.g. <code>&quot;Design, Analysis of Algorithms&quot;</code>. For
          an <code>ELECTIVE</code>, list the eligible department codes separated by <code>|</code>{" "}
          (e.g. <code>CS|CV</code>); leave that column empty for a <code>REGULAR</code> subject.
          Subjects already in the catalog for this year are skipped, so it&apos;s safe to re-run.
          Preview first to check.
        </>
      }
      controls={
        <>
          <Field label="Academic year" htmlFor="import-year">
            <select
              id="import-year"
              className={`${FIELD_CONTROL} w-40`}
              value={year}
              onChange={(e) => setYear(e.target.value)}
              data-cy="subjects-import-year"
            >
              <option value="">Select year</option>
              {recentAcademicYears(year).map((y) => (
                <option key={y} value={y}>
                  {formatAcademicYear(y)}
                </option>
              ))}
            </select>
          </Field>
          {!deptLocked && (
            <Field label="Department" htmlFor="import-dept">
              <select
                id="import-dept"
                className={`${FIELD_CONTROL} w-56`}
                value={deptId}
                onChange={(e) => setDeptId(e.target.value)}
                data-cy="subjects-import-dept"
              >
                <option value="">Select department</option>
                <DepartmentOptions departments={departments} />
              </select>
            </Field>
          )}
        </>
      }
    />
  );
}

export default ImportSubjectsTab;
