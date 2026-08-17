import { useCallback, useEffect, useState } from "react";
import { History, LoaderCircle, TrendingUp, TriangleAlert } from "lucide-react";
import MagneticCta from "../../components/ui/MagneticCta";
import AlertBanner from "../../components/AlertBanner";
import api, { getAdminHeaders } from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";
import { CURRENT_SEMESTERS } from "../../lib/semesters";

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

const CONFIRM_WORD = "PROMOTE";

const OUTCOME_LABEL = {
  EXCLUDED_BY_ADMIN: "Held back (your list)",
  SKIPPED_AT_MAX: "Already in semester 8",
  SKIPPED_INVALID_SEMESTER: "Invalid semester — fix by hand",
};

/**
 * Year-end promotion: every student in the selection moves +2, except the USNs pasted below.
 *
 * ADMIN-only; the server enforces it with hasRole('ADMIN') and this tab is only rendered for that
 * role. Two-step on purpose — the preview's promoteCount is echoed back as expectedCount, so a
 * second click after a successful run carries a count the server no longer agrees with and 409s.
 * That is the double-run guard; the typed word is only there to slow the first click down.
 */
function BulkProgressionTab({ departments }) {
  const [semester, setSemester] = useState("");
  const [deptCode, setDeptCode] = useState("");
  const [exclusions, setExclusions] = useState("");

  const [preview, setPreview] = useState(null);
  const [confirmWord, setConfirmWord] = useState("");
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // past runs — the audit tables exist precisely so a completed run stays inspectable
  const [history, setHistory] = useState(null); // null = not loaded yet
  const [historyError, setHistoryError] = useState("");
  const [openBatch, setOpenBatch] = useState(null);
  const [detail, setDetail] = useState(null);
  const [detailBusy, setDetailBusy] = useState(false);

  const excludeRollNos = exclusions
    .split(/[\s,]+/)
    .map((s) => s.trim())
    .filter(Boolean);

  const body = {
    semester: semester === "" ? null : Number(semester),
    deptCode: deptCode.trim() === "" ? null : deptCode.trim(),
    excludeRollNos,
  };

  // any edit invalidates a preview — committing against a stale count is exactly what the
  // server's 409 catches, but there is no reason to let the user get that far
  const resetPreview = () => {
    setPreview(null);
    setConfirmWord("");
    setResult(null);
  };

  const loadHistory = useCallback(() => {
    api
      .get("/admin/progression/bulk", { headers: getAdminHeaders(), params: { page: 0, size: 10 } })
      .then((res) => {
        // Spring Page envelope, not a bare array
        setHistory(res.data?.content ?? []);
        setHistoryError("");
      })
      // a swallowed failure here leaves the panel silently empty, which reads as "no runs yet"
      .catch((err) => reportLoadError(err, setHistoryError, "Could not load past runs."));
  }, []);

  // Fetch-on-mount into an external system. Not one of the 9 known set-state-in-effect errors:
  // the state lands inside .then, so the rule doesn't fire. Keep it that way.
  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const toggleBatch = async (batchId) => {
    if (openBatch === batchId) {
      setOpenBatch(null);
      return;
    }
    setOpenBatch(batchId);
    setDetail(null);
    setDetailBusy(true);
    try {
      const res = await api.get(`/admin/progression/bulk/${batchId}`, { headers: getAdminHeaders() });
      setDetail(res.data);
    } catch (err) {
      setHistoryError(err.response?.data?.message || "Could not load that run.");
      setOpenBatch(null);
    }
    setDetailBusy(false);
  };

  const runPreview = async (e) => {
    e.preventDefault();
    setError("");
    setResult(null);
    setBusy(true);
    try {
      // paths are relative to api.js's /api baseURL — a leading /api here doubles it
      const res = await api.post("/admin/progression/bulk/preview", body, {
        headers: getAdminHeaders(),
      });
      setPreview(res.data);
      setConfirmWord("");
    } catch (err) {
      setError(err.response?.data?.message || "Could not preview the run.");
      setPreview(null);
    }
    setBusy(false);
  };

  const commit = async () => {
    setError("");
    setBusy(true);
    try {
      const res = await api.post(
        "/admin/progression/bulk",
        { ...body, expectedCount: preview.promoteCount },
        { headers: getAdminHeaders() },
      );
      setResult(res.data);
      setPreview(null);
      setConfirmWord("");
      loadHistory(); // the run just made is the newest row
    } catch (err) {
      setError(err.response?.data?.message || "Could not run the promotion.");
    }
    setBusy(false);
  };

  const card = "rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft";

  return (
    <div className="flex flex-col gap-4">
      <AlertBanner tone="warning" icon={<TriangleAlert size={16} />}>
        This advances <strong>every student</strong> in the selection by two semesters (2&nbsp;→&nbsp;4,
        4&nbsp;→&nbsp;6, 6&nbsp;→&nbsp;8). Students already in semester 8 stay put. It does not touch
        anyone's academic-year timeline — correct a detained student's years from their Semesters
        panel on the Manage tab. Registration must be closed before it will run.
      </AlertBanner>

      {error && (
        <AlertBanner tone="error" data-cy="bulk-progression-error">
          {error}
        </AlertBanner>
      )}

      <form onSubmit={runPreview} className={card}>
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="bulk-sem" className="text-xs font-semibold uppercase tracking-[0.08em]">
              Semester
            </label>
            <select
              id="bulk-sem"
              className={`${inputClass} w-40`}
              value={semester}
              onChange={(e) => {
                setSemester(e.target.value);
                resetPreview();
              }}
              data-cy="bulk-semester"
            >
              <option value="">All semesters</option>
              {CURRENT_SEMESTERS.map((s) => (
                <option key={s} value={s}>
                  Semester {s}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="bulk-dept" className="text-xs font-semibold uppercase tracking-[0.08em]">
              Department
            </label>
            <select
              id="bulk-dept"
              className={`${inputClass} w-52`}
              value={deptCode}
              onChange={(e) => {
                setDeptCode(e.target.value);
                resetPreview();
              }}
              data-cy="bulk-dept"
            >
              <option value="">All departments</option>
              {departments.map((d) => (
                <option key={d.id} value={d.code}>
                  {d.deptName}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="bulk-exclude" className="text-xs font-semibold uppercase tracking-[0.08em]">
            Hold back these USNs
          </label>
          <textarea
            id="bulk-exclude"
            className={`${inputClass} min-h-24 font-mono`}
            value={exclusions}
            placeholder="1MS24CS001, 1MS24CS002 — separated by spaces, commas or new lines"
            onChange={(e) => {
              setExclusions(e.target.value);
              resetPreview();
            }}
            data-cy="bulk-exclusions"
          />
          <p className="text-xs text-ink-muted">
            Detained students go here. A USN that isn't a real student stops the whole run — a typo
            must never silently promote someone you meant to hold back.
            {excludeRollNos.length > 0 && ` ${excludeRollNos.length} listed.`}
          </p>
        </div>

        <MagneticCta as="button" type="submit" disabled={busy} className="mt-3" data-cy="bulk-preview">
          {busy ? <LoaderCircle size={15} className="animate-spin" /> : <TrendingUp size={15} />}
          Preview
        </MagneticCta>
      </form>

      {preview && (
        <div className={card} data-cy="bulk-preview-result">
          <p className="text-sm font-semibold text-ink">
            <span data-cy="bulk-promote-count">{preview.promoteCount}</span> student
            {preview.promoteCount === 1 ? "" : "s"} will advance two semesters.
          </p>
          <p className="mt-1 text-xs text-ink-muted" data-cy="bulk-at-max">
            {preview.atMaxCount} already in semester 8 — skipped, not listed.
          </p>
          {preview.notPromoted.length > 0 && (
            <div className="mt-3">
              <p className="mb-2 text-xs font-semibold uppercase tracking-[0.08em] text-ink-muted">
                Needs your attention ({preview.notPromoted.length})
              </p>
              <div className="max-h-64 overflow-y-auto rounded-xl border border-stroke">
                <table className="w-full text-left text-sm">
                  <thead className="bg-surface-muted text-xs uppercase tracking-[0.08em] text-ink-muted">
                    <tr>
                      <th className="px-3 py-2">USN</th>
                      <th className="px-3 py-2">Sem</th>
                      <th className="px-3 py-2">Reason</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.notPromoted.map((r) => (
                      <tr key={r.rollNo} className="border-t border-stroke">
                        <td className="px-3 py-2 font-mono text-xs">{r.rollNo}</td>
                        <td className="px-3 py-2">{r.currentSemester}</td>
                        <td className="px-3 py-2 text-ink-muted">
                          {OUTCOME_LABEL[r.outcome] || r.outcome}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {preview.promoteCount > 0 && (
            <div className="mt-4 border-t border-stroke pt-3">
              <label htmlFor="bulk-confirm" className="text-xs font-semibold uppercase tracking-[0.08em]">
                Type {CONFIRM_WORD} to run
              </label>
              <div className="mt-1.5 flex flex-wrap items-center gap-2">
                <input
                  id="bulk-confirm"
                  className={`${inputClass} w-48 font-mono`}
                  value={confirmWord}
                  onChange={(e) => setConfirmWord(e.target.value.toUpperCase())}
                  data-cy="bulk-confirm-word"
                />
                <MagneticCta
                  as="button"
                  type="button"
                  onClick={commit}
                  disabled={busy || confirmWord !== CONFIRM_WORD}
                  data-cy="bulk-commit"
                >
                  {busy ? <LoaderCircle size={15} className="animate-spin" /> : <TrendingUp size={15} />}
                  Promote {preview.promoteCount}
                </MagneticCta>
              </div>
            </div>
          )}
        </div>
      )}

      {result && (
        <AlertBanner tone="success" data-cy="bulk-result">
          Promoted {result.promotedCount} student{result.promotedCount === 1 ? "" : "s"}.{" "}
          {result.excludedCount} held back, {result.skippedCount} skipped. Recorded as batch #
          {result.batchId} — open it below.
        </AlertBanner>
      )}

      {/* Past runs. Without this the audit is written and never surfaced, and the batch id above
          names a record the admin has no way to look at. */}
      <div className={card} data-cy="bulk-history">
        <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-secondary-ink">
          <History size={15} /> Past runs
        </h3>
        {historyError && (
          <AlertBanner tone="error" compact data-cy="bulk-history-error">
            {historyError}
          </AlertBanner>
        )}
        {history && history.length === 0 && !historyError && (
          <p className="text-xs text-ink-muted" data-cy="bulk-history-empty">
            No promotions have been run yet.
          </p>
        )}
        {history && history.length > 0 && (
          <ul className="flex flex-col gap-2">
            {history.map((b) => (
              <li key={b.batchId} className="rounded-xl border border-stroke">
                <button
                  type="button"
                  onClick={() => toggleBatch(b.batchId)}
                  className="flex w-full flex-wrap items-center justify-between gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-surface-muted"
                  data-cy={`bulk-history-row-${b.batchId}`}
                  aria-expanded={openBatch === b.batchId}
                >
                  <span className="font-semibold">
                    #{b.batchId} — {b.promotedCount} promoted
                  </span>
                  <span className="text-xs text-ink-muted">
                    {b.filterDeptCode || "All departments"}
                    {b.filterSemester ? `, semester ${b.filterSemester}` : ""} · by {b.actor} ·{" "}
                    {b.runAt ? b.runAt.slice(0, 10) : ""}
                  </span>
                </button>
                {openBatch === b.batchId && (
                  <div className="border-t border-stroke px-3 py-2" data-cy="bulk-history-detail">
                    {detailBusy && <LoaderCircle size={14} className="animate-spin" />}
                    {detail && (
                      <>
                        <p className="text-xs text-ink-muted">
                          {detail.batch.promotedCount} promoted, {detail.batch.excludedCount} held
                          back, {detail.batch.skippedCount} skipped.
                        </p>
                        {detail.notPromoted.length > 0 && (
                          <ul className="mt-2 flex flex-col gap-1">
                            {detail.notPromoted.map((r) => (
                              <li key={r.rollNo} className="text-xs">
                                <span className="font-mono">{r.rollNo}</span>{" "}
                                <span className="text-ink-muted">
                                  (sem {r.semesterFrom}) — {OUTCOME_LABEL[r.outcome] || r.outcome}
                                </span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export default BulkProgressionTab;
