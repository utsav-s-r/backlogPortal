import { useState, useCallback } from "react";
import { Download, LoaderCircle, Search, UploadCloud } from "lucide-react";
import MagneticCta from "../../components/ui/MagneticCta";
import api, { getAdminHeaders } from "../../lib/api";
import BatchResultTable from "./BatchResultTable";
import { saveBlob } from "../../lib/download";
import { CURRENT_SEMESTERS, ENTRY_SEMESTERS } from "../../lib/semesters";

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

const HEADER = "USN,name,phone,dateOfBirth,currentSemester,entrySemester";
const TEMPLATE =
  HEADER + "\n1MS24CS001,Asha Rao,9999999999,2006-04-12,1,1\n1MS24CS002,Migrant Kid,,2005-09-01,3,3";

// Parse a CSV body into import rows; blank semester cells fall back to the batch defaults
// server-side, and a header line is skipped if present.
function parseCsv(text) {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line && !/^usn|^rollno/i.test(line))
    .map((line) => {
      const [rollNo, name, phone, dateOfBirth, currentSemester, entrySemester] = line
        .split(",")
        .map((c) => (c == null ? "" : c.trim()));
      return {
        rollNo,
        name,
        phone: phone ? phone.replace(/\D/g, "").slice(0, 10) : null,
        dateOfBirth: dateOfBirth || null,
        currentSemester: currentSemester ? Number(currentSemester) : null,
        entrySemester: entrySemester ? Number(entrySemester) : null,
      };
    });
}

// Bulk-import students from CSV. Presentational tab: per-row semesters fall back to the batch
// defaults, existing USNs are skipped, and dryRun previews without writing.
function ImportStudentsTab() {
  const [csv, setCsv] = useState("");
  const [defaultCurrent, setDefaultCurrent] = useState("2");
  const [defaultEntry, setDefaultEntry] = useState("1");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);

  const run = useCallback(
    async (dryRun) => {
      setError("");
      // the card describes ONE run; cleared here, not in the catch, so the empty-CSV early-return
      // below is covered too
      setResult(null);
      const rows = parseCsv(csv);
      if (rows.length === 0) {
        setError("Paste at least one row: " + HEADER);
        return;
      }
      setBusy(true);
      try {
        const res = await api.post(
          "/admin/students/import",
          {
            rows,
            defaultCurrentSemester: defaultCurrent ? Number(defaultCurrent) : null,
            defaultEntrySemester: defaultEntry ? Number(defaultEntry) : null,
            dryRun,
          },
          { headers: getAdminHeaders() },
        );
        setResult(res.data);
      } catch (err) {
        setError(err.response?.data?.message || "Import failed.");
      } finally {
        setBusy(false);
      }
    },
    [csv, defaultCurrent, defaultEntry],
  );

  // saveBlob, not a hand-rolled anchor: an immediate revokeObjectURL cancels the download outright
  // on iOS Safari, which consumes the blob URL asynchronously.
  const downloadTemplate = () => saveBlob(TEMPLATE, "students-template.csv", "text/csv");

  return (
    <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
      <h2 className="mb-1 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
        <UploadCloud size={18} /> Import students (CSV)
      </h2>
      <p className="mb-3 text-sm text-ink-muted">
        One row per line: <code>{HEADER}</code>. Date of birth is <code>yyyy-MM-dd</code>. Phone is
        optional; email is assigned automatically as <code>usn@msrit.edu</code>. Leave the two semester
        columns blank to use the batch defaults below. Existing USNs are skipped, so it's safe to re-run.
        Preview first to check.
      </p>

      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Default current sem</label>
          <select
            className={`${inputClass} w-32`}
            value={defaultCurrent}
            onChange={(e) => setDefaultCurrent(e.target.value)}
            data-cy="students-import-default-current"
          >
            {CURRENT_SEMESTERS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Default entry sem</label>
          <select
            className={`${inputClass} w-32`}
            value={defaultEntry}
            onChange={(e) => setDefaultEntry(e.target.value)}
            data-cy="students-import-default-entry"
          >
            {ENTRY_SEMESTERS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <button
          type="button"
          onClick={downloadTemplate}
          className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-3 py-2 text-sm font-semibold transition-colors hover:border-primary"
          data-cy="students-import-template"
        >
          <Download size={15} /> Template
        </button>
      </div>

      <textarea
        className={`${inputClass} min-h-32 font-mono`}
        placeholder={"1MS24CS001,Asha Rao,9999999999,2006-04-12,1,1"}
        value={csv}
        onChange={(e) => setCsv(e.target.value)}
        data-cy="students-import-csv"
      />

      {error && (
        <p className="mt-3 text-sm text-red-600" role="alert" data-cy="students-import-error">
          {error}
        </p>
      )}

      <div className="mt-4 flex flex-wrap gap-3">
        <button
          type="button"
          onClick={() => run(true)}
          disabled={busy}
          data-cy="students-import-preview"
          className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary disabled:opacity-60"
        >
          {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />} Preview
        </button>
        <MagneticCta
          type="button"
          onClick={() => run(false)}
          disabled={busy}
          className="gap-2 rounded-xl"
          data-cy="students-import-apply"
        >
          <UploadCloud size={15} /> Import
        </MagneticCta>
      </div>

      <BatchResultTable result={result} verb="Imported" dataCy="students-import-result" />
    </section>
  );
}

export default ImportStudentsTab;
