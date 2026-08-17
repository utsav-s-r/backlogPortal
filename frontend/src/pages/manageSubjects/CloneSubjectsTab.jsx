import { useState, useCallback, useMemo, useRef } from "react";
import {
  CheckCircle2,
  Copy,
  LoaderCircle,
  Search,
  Trash2,
} from "lucide-react";
import MagneticCta from "../../components/ui/MagneticCta";
import api, { getAdminHeaders } from "../../lib/api";
import { formatAcademicYear, parseAcademicYear } from "../../lib/academicYear";
import CourseCodeField from "../../components/ui/CourseCodeField";

const ALL_SEMS = [1, 2, 3, 4, 5, 6, 7, 8];

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

const STATUS_STYLES = {
  CREATED: "text-primary-ink",
  WOULD_CREATE: "text-primary-ink",
  SKIPPED_EXISTS: "text-ink-muted",
  WOULD_SKIP: "text-ink-muted",
  ERROR: "text-red-600",
};

// Clone a department's subjects into the next academic year. Presentational tab: the shell
// supplies departments and the dept-lock context.
function CloneSubjectsTab({ departments, adminDepartment, deptLocked, pinnedDeptId }) {
  // Per-instance, not module scope: a shared counter outlives every mount and only ever grows.
  // Its sole job is a stable React key for rows that have no id until they are created.
  const rowKeySeq = useRef(0);
  const [deptId, setDeptId] = useState("");
  const [sourceYear, setSourceYear] = useState("");
  const [targetYear, setTargetYear] = useState("");
  const [semesters, setSemesters] = useState([...ALL_SEMS]);

  const [rows, setRows] = useState(null); // null = no preview yet
  const [previewYears, setPreviewYears] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);

  const toggleSem = (s) =>
    setSemesters((prev) =>
      prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s].sort((a, b) => a - b),
    );

  const effectiveDeptId = deptLocked ? pinnedDeptId : deptId;

  const runPreview = useCallback(async () => {
    setError("");
    setResult(null);
    const src = parseAcademicYear(sourceYear);
    const tgt = parseAcademicYear(targetYear);
    if (!effectiveDeptId) {
      setError("Select a department.");
      return;
    }
    if (Number.isNaN(src) || Number.isNaN(tgt)) {
      setError("Enter the source and target years (e.g. 2024-25).");
      return;
    }
    if (semesters.length === 0) {
      setError("Select at least one semester.");
      return;
    }
    setBusy(true);
    try {
      const res = await api.post(
        "/admin/subjects/clone/preview",
        { deptId: Number(effectiveDeptId), sourceYear: src, targetYear: tgt, semesters },
        { headers: getAdminHeaders() },
      );
      const previewed = (res.data?.rows || []).map((r) => ({
        ...r,
        _key: ++rowKeySeq.current,
        removed: false,
      }));
      setRows(previewed);
      setPreviewYears({ source: res.data.sourceYear, target: res.data.targetYear });
    } catch (err) {
      setError(err.response?.data?.message || "Preview failed.");
    } finally {
      setBusy(false);
    }
  }, [effectiveDeptId, sourceYear, targetYear, semesters]);

  const updateRow = (key, field, value) =>
    setRows((prev) => prev.map((r) => (r._key === key ? { ...r, [field]: value } : r)));

  const toggleRemove = (key) =>
    setRows((prev) => prev.map((r) => (r._key === key ? { ...r, removed: !r.removed } : r)));

  // ERROR rows are excluded too: they cannot be created (e.g. a course code with no year prefix),
  // so submitting them would just produce guaranteed per-row failures on apply.
  // Memoised on `rows`: rebuilding this array every render gave runApply's useCallback a new
  // dependency each time, so the memoisation below was doing nothing.
  const applicableRows = useMemo(
    () =>
      (rows || []).filter(
        (r) => !r.removed && r.status !== "WOULD_SKIP" && r.status !== "ERROR",
      ),
    [rows],
  );

  const runApply = useCallback(async () => {
    setError("");
    if (applicableRows.length === 0) {
      setError("Nothing to create — every row is skipped or removed.");
      return;
    }
    setBusy(true);
    try {
      const payload = {
        deptId: Number(effectiveDeptId),
        targetYear: previewYears.target,
        rows: applicableRows.map((r) => ({
          subjectName: r.subjectName,
          courseCode: r.courseCode,
          semester: r.semester,
          credits: Number(r.credits),
          subjectType: r.subjectType,
          eligibleDeptIds: r.eligibleDeptIds || [],
        })),
      };
      const res = await api.post("/admin/subjects/clone/apply", payload, {
        headers: getAdminHeaders(),
      });
      setResult(res.data);
      // reconcile statuses in place, avoiding a refetch and keeping the result banner:
      // just-created rows now "exist" and drop out of the applicable set
      const byCode = new Map((res.data?.rows || []).map((rr) => [rr.courseCode, rr]));
      setRows((prev) =>
        prev.map((r) => {
          if (r.removed) return r;
          const rr = byCode.get(r.courseCode);
          if (!rr) return r;
          if (rr.status === "ERROR") return { ...r, status: "ERROR", message: rr.message };
          return { ...r, status: "WOULD_SKIP", message: rr.message || "Created" };
        }),
      );
    } catch (err) {
      setError(err.response?.data?.message || "Apply failed.");
    } finally {
      setBusy(false);
    }
  }, [applicableRows, effectiveDeptId, previewYears]);

  return (
    <>
      <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
        <h1 className="mb-1 inline-flex items-center gap-2 text-xl font-semibold text-secondary-ink">
          <Copy size={18} /> Clone subjects to a new year
        </h1>
        <p className="mb-4 text-sm text-ink-muted">
          Copy a department's subjects from one academic year into the next. Course codes and the
          year are bumped automatically; review and edit before saving. Existing subjects are
          skipped, so it's safe to re-run.
        </p>
        {deptLocked && adminDepartment && (
          <p className="mb-4 text-xs font-semibold text-primary-ink">
            Scoped to {adminDepartment}
          </p>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Department</label>
            <select
              className={inputClass}
              value={effectiveDeptId}
              onChange={(e) => setDeptId(e.target.value)}
              disabled={deptLocked}
              data-cy="clone-dept"
            >
              <option value="">Select department</option>
              {departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.deptName}
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-semibold uppercase tracking-[0.08em]">From year</label>
              <input
                className={inputClass}
                type="text"
                placeholder="e.g. 2024-25"
                value={sourceYear}
                onChange={(e) => setSourceYear(e.target.value)}
                data-cy="clone-source-year"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-semibold uppercase tracking-[0.08em]">To year</label>
              <input
                className={inputClass}
                type="text"
                placeholder="e.g. 2025-26"
                value={targetYear}
                onChange={(e) => setTargetYear(e.target.value)}
                data-cy="clone-target-year"
              />
            </div>
          </div>
        </div>

        <div className="mt-4">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold uppercase tracking-[0.08em]">Semesters</span>
            <button type="button" onClick={() => setSemesters([...ALL_SEMS])} className="text-xs font-semibold text-primary-ink hover:underline">All</button>
            <button type="button" onClick={() => setSemesters([1, 3, 5, 7])} className="text-xs font-semibold text-primary-ink hover:underline">Odd</button>
            <button type="button" onClick={() => setSemesters([2, 4, 6, 8])} className="text-xs font-semibold text-primary-ink hover:underline">Even</button>
            <button type="button" onClick={() => setSemesters([])} className="text-xs font-semibold text-ink-muted hover:underline">None</button>
          </div>
          <div className="flex flex-wrap gap-2">
            {ALL_SEMS.map((s) => {
              const on = semesters.includes(s);
              return (
                <button
                  key={s}
                  type="button"
                  onClick={() => toggleSem(s)}
                  data-cy={`clone-sem-${s}`}
                  className={`rounded-lg border px-3 py-1.5 text-sm font-semibold transition-colors ${
                    on
                      ? "border-primary bg-primary-tint text-primary-ink"
                      : "border-stroke bg-surface-muted text-ink"
                  }`}
                >
                  {s}
                </button>
              );
            })}
          </div>
        </div>

        {error && (
          <p className="mt-3 text-sm text-red-600" role="alert" data-cy="clone-error">
            {error}
          </p>
        )}

        <div className="mt-4">
          <button
            type="button"
            onClick={runPreview}
            disabled={busy}
            data-cy="clone-preview"
            className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary disabled:opacity-60"
          >
            {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />} Preview
          </button>
        </div>
      </section>

      {rows && (
        <section className="mt-6 rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
          <h2 className="mb-1 text-lg font-semibold text-secondary-ink">
            Draft for {formatAcademicYear(previewYears?.target)}
          </h2>
          <p className="mb-4 text-sm text-ink-muted">
            {applicableRows.length} to create · {rows.filter((r) => r.status === "WOULD_SKIP").length} already exist ·
            edit any field, or remove a row that isn't offered next year.
          </p>

          {rows.length === 0 ? (
            <p className="rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
              No subjects found for {formatAcademicYear(previewYears?.source)} in the selected
              department and semesters.
            </p>
          ) : (
            <div className="overflow-auto rounded-xl border border-stroke">
              <table className="w-full text-left text-sm">
                <thead className="bg-surface-muted text-xs uppercase tracking-[0.08em] text-ink-muted">
                  <tr>
                    <th className="px-3 py-2">Sem</th>
                    <th className="px-3 py-2">Subject name</th>
                    <th className="px-3 py-2">Course code</th>
                    <th className="px-3 py-2">Credits</th>
                    <th className="px-3 py-2">Status</th>
                    <th className="px-3 py-2">Detail</th>
                    <th className="px-3 py-2"></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr
                      key={r._key}
                      className={`border-t border-stroke ${r.removed ? "opacity-40" : ""}`}
                    >
                      <td className="px-3 py-2">{r.semester}</td>
                      <td className="px-3 py-2">
                        <input
                          className={`${inputClass} min-w-44`}
                          value={r.subjectName || ""}
                          onChange={(e) => updateRow(r._key, "subjectName", e.target.value)}
                          disabled={r.removed}
                        />
                      </td>
                      <td className="px-3 py-2">
                        <CourseCodeField
                          year={previewYears?.target}
                          value={r.courseCode || ""}
                          onChange={(code) => updateRow(r._key, "courseCode", code)}
                          inputClassName={`${inputClass} w-24`}
                          disabled={r.removed}
                          dataCy={`clone-row-code-${r.semester}`}
                        />
                      </td>
                      <td className="px-3 py-2">
                        <input
                          className={`${inputClass} w-20`}
                          type="number"
                          min="0"
                          value={r.credits}
                          onChange={(e) => updateRow(r._key, "credits", e.target.value)}
                          disabled={r.removed}
                        />
                      </td>
                      <td className={`px-3 py-2 font-semibold ${STATUS_STYLES[r.status] || ""}`}>
                        {r.status === "WOULD_SKIP" ? "Exists" : r.status === "ERROR" ? "Error" : "New"}
                      </td>
                      {/* The server's per-row reason. Without this an ERROR row read just "Error",
                          so a subject that silently failed to clone into the new year gave the
                          admin nothing to act on. Same Detail column as ImportStudentsTab. */}
                      <td className="px-3 py-2 text-ink-muted" data-cy={`clone-row-detail-${r.semester}`}>
                        {r.message || ""}
                      </td>
                      <td className="px-3 py-2">
                        <button
                          type="button"
                          onClick={() => toggleRemove(r._key)}
                          className="inline-flex items-center gap-1 rounded-md border border-stroke px-2 py-1 text-xs font-semibold text-ink transition-colors hover:border-primary"
                          data-cy={`clone-row-remove-${r.semester}`}
                        >
                          <Trash2 size={13} /> {r.removed ? "Undo" : "Remove"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {rows.length > 0 && (
            <div className="mt-4">
              <MagneticCta
                type="button"
                onClick={runApply}
                disabled={busy || applicableRows.length === 0}
                className="gap-2 rounded-xl disabled:opacity-60"
                data-cy="clone-apply"
              >
                {busy ? <LoaderCircle size={16} className="animate-spin" /> : <CheckCircle2 size={16} />}
                Create {applicableRows.length} subject(s)
              </MagneticCta>
            </div>
          )}

          {result && (
            <div className="mt-4 rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm" data-cy="clone-result">
              Done — {result.created} created, {result.skipped} skipped, {result.errors} error(s).
            </div>
          )}
        </section>
      )}
    </>
  );
}

export default CloneSubjectsTab;
