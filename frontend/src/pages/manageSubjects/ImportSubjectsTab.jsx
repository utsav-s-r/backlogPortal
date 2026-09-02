import { useState, useCallback } from "react";
import { Download, LoaderCircle, Search, UploadCloud } from "lucide-react";
import MagneticCta from "../../components/ui/MagneticCta";
import api from "../../lib/api";
import BatchResultTable from "../../components/ui/BatchResultTable";
import DepartmentOptions from "../../components/ui/DepartmentOptions";
import Field from "../../components/ui/Field";
import { saveBlob } from "../../lib/download";
import { formatAcademicYear, recentAcademicYears } from "../../lib/academicYear";
import { FIELD_CONTROL, FIELD_INPUT } from "../../lib/formClasses";
import { parseSubjectCsv, SUBJECT_CSV_HEADER, SUBJECT_CSV_TEMPLATE } from "./subjectImportCsv";

// Bulk-load a year's subject catalog from CSV — the first-load path, where the Clone tab can't help
// because there is no previous year to copy from. Presentational: the shell supplies departments
// and the dept-lock context.
//
// The academic year is chosen ONCE here, not per row, and the server stamps it on every row. It is
// the year-binding key and year-binding is fail-closed, so a mistyped year yields subjects that
// look right in the catalog and are invisible to the students who need them.
function ImportSubjectsTab({ departments, deptLocked, pinnedDeptId }) {
  const [csv, setCsv] = useState("");
  const [year, setYear] = useState("");
  const [deptId, setDeptId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);

  const effectiveDeptId = deptLocked ? pinnedDeptId : deptId;

  const run = useCallback(
    async (dryRun) => {
      setError("");
      // the card describes ONE run; cleared here rather than in the catch so the early returns
      // below are covered too
      setResult(null);

      if (!year) {
        setError("Choose the academic year these subjects are offered in.");
        return;
      }
      if (!effectiveDeptId) {
        setError("Choose the department.");
        return;
      }

      let rows;
      try {
        rows = parseSubjectCsv(csv);
      } catch (parseError) {
        // parseSubjectCsv throws only on malformed quoting, with a message written for an admin
        setError(parseError.message);
        return;
      }
      if (rows.length === 0) {
        setError("Paste at least one row: " + SUBJECT_CSV_HEADER);
        return;
      }

      setBusy(true);
      try {
        const res = await api.post("/admin/subjects/import", {
          rows,
          deptId: Number(effectiveDeptId),
          academicYearOffered: Number(year),
          dryRun,
        });
        setResult(res.data);
      } catch (err) {
        setError(err.response?.data?.message || "Import failed.");
      } finally {
        setBusy(false);
      }
    },
    [csv, year, effectiveDeptId],
  );

  // saveBlob, not a hand-rolled anchor: an immediate revokeObjectURL cancels the download outright
  // on iOS Safari, which consumes the blob URL asynchronously.
  const downloadTemplate = () => saveBlob(SUBJECT_CSV_TEMPLATE, "subjects-template.csv", "text/csv");

  return (
    <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
      <h2 className="mb-1 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
        <UploadCloud size={18} /> Import subjects (CSV)
      </h2>
      <p className="mb-3 text-sm text-ink-muted">
        One row per line: <code>{SUBJECT_CSV_HEADER}</code>. The academic year and department are set
        below and applied to every row, so they are not columns. Wrap a value containing a comma in
        double quotes, e.g. <code>&quot;Design, Analysis of Algorithms&quot;</code>. For an{" "}
        <code>ELECTIVE</code>, list the eligible department codes separated by <code>|</code> (e.g.{" "}
        <code>CS|CV</code>); leave that column empty for a <code>REGULAR</code> subject. Subjects
        already in the catalog for this year are skipped, so it&apos;s safe to re-run. Preview first
        to check.
      </p>

      <div className="mb-3 flex flex-wrap items-end gap-3">
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
        <button
          type="button"
          onClick={downloadTemplate}
          className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-3 py-2 text-sm font-semibold transition-colors hover:border-primary"
          data-cy="subjects-import-template"
        >
          <Download size={15} /> Template
        </button>
      </div>

      <textarea
        className={`${FIELD_INPUT} min-h-32 font-mono`}
        placeholder="CSL44,Data Structures,4,4,REGULAR,"
        value={csv}
        onChange={(e) => setCsv(e.target.value)}
        data-cy="subjects-import-csv"
      />

      {error && (
        <p className="mt-3 text-sm text-red-600" role="alert" data-cy="subjects-import-error">
          {error}
        </p>
      )}

      <div className="mt-4 flex flex-wrap gap-3">
        <button
          type="button"
          onClick={() => run(true)}
          disabled={busy}
          data-cy="subjects-import-preview"
          className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary disabled:opacity-60"
        >
          {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />} Preview
        </button>
        <MagneticCta
          type="button"
          onClick={() => run(false)}
          disabled={busy}
          className="gap-2 rounded-xl"
          data-cy="subjects-import-apply"
        >
          <UploadCloud size={15} /> Import
        </MagneticCta>
      </div>

      <BatchResultTable
        result={result}
        verb="Imported"
        dataCy="subjects-import-result"
        idLabel="Course code"
        idKey="courseCode"
      />
    </section>
  );
}

export default ImportSubjectsTab;
