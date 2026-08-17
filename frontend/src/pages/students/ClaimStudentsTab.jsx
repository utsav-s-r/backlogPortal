import { useState, useCallback, useEffect } from "react";
import {
  Check,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  Search,
  UserCheck,
  UserMinus,
  Users,
} from "lucide-react";
import api, { getAdminHeaders } from "../../lib/api";
import { batchRows } from "./batchResult";
import { reportLoadError } from "../../lib/loadError";
import { ALL_SEMESTERS } from "../../lib/semesters";

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

const PAGE_SIZE = 25;

// Proctor supervision assignments, shared by two audiences:
//   - a PROCTOR claims dept students to themselves; the minimal claim picker is their only view
//     of students outside their own set;
//   - ADMIN / PRINCIPAL / HOD pick a target proctor first, then manage that proctor's list
//     (assign / unassign / reassign after a conflict).
// Scope rules are enforced server-side on /api/admin/proctor/**; this UI only mirrors them.
function ClaimStudentsTab({ adminRole, adminDepartment }) {
  const isProctor = adminRole === "PROCTOR";

  // staff callers must name a target proctor
  const [proctors, setProctors] = useState([]);
  const [targetProctor, setTargetProctor] = useState("");

  // picker filters
  const [fYear, setFYear] = useState("");
  const [fSemester, setFSemester] = useState("");
  const [fQuery, setFQuery] = useState("");

  const [rows, setRows] = useState(null); // null = not loaded yet
  const [pageInfo, setPageInfo] = useState({ number: 0, totalPages: 0, totalElements: 0 });
  const [selected, setSelected] = useState(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [results, setResults] = useState(null); // per-row claim outcomes

  // current assignments of the target
  const [assigned, setAssigned] = useState(null);
  const [assignedBusy, setAssignedBusy] = useState(false);
  const [removingRoll, setRemovingRoll] = useState("");

  // NOTE: react-hooks/set-state-in-effect may flag this — intended and correct, a fetch-on-mount
  // into an external system (state lands in .then). Staff need the proctor list to pick a target;
  // the server already limits /admin/users to accounts the caller may manage.
  useEffect(() => {
    if (isProctor) return;
    api
      .get("/admin/users", { headers: getAdminHeaders() })
      .then((res) => {
        setProctors((res.data || []).filter((u) => u.role === "PROCTOR"));
        setError("");
      })
      // without this the target-proctor picker is silently empty and HOD/admin cannot assign
      .catch((err) =>
        reportLoadError(err, setError, "Could not load the proctor list. Refresh to retry."),
      );
  }, [isProctor]);

  const proctorParam = isProctor ? "" : targetProctor;
  const targetChosen = isProctor || Boolean(targetProctor);

  const loadAssigned = useCallback(async () => {
    if (!targetChosen) return;
    setAssignedBusy(true);
    try {
      const params = {};
      if (proctorParam) params.proctor = proctorParam;
      const res = await api.get("/admin/proctor/students", {
        headers: getAdminHeaders(),
        params,
      });
      setAssigned(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      setError(err.response?.data?.message || "Could not load assigned students.");
    } finally {
      setAssignedBusy(false);
    }
  }, [targetChosen, proctorParam]);

  const loadClaimable = useCallback(
    async (targetPage = 0) => {
      if (!targetChosen) {
        setError("Select a proctor first.");
        return;
      }
      setError("");
      setBusy(true);
      try {
        const params = { page: targetPage, size: PAGE_SIZE };
        if (proctorParam) params.proctor = proctorParam;
        if (fYear && /^\d{4}$/.test(fYear.trim())) params.admissionYear = Number(fYear.trim());
        if (fSemester) params.semester = Number(fSemester);
        if (fQuery.trim()) params.query = fQuery.trim();
        const res = await api.get("/admin/proctor/claimable", {
          headers: getAdminHeaders(),
          params,
        });
        const data = res.data || {};
        setRows(Array.isArray(data.content) ? data.content : []);
        setPageInfo({
          number: data.number ?? 0,
          totalPages: data.totalPages ?? 0,
          totalElements: data.totalElements ?? 0,
        });
        setSelected(new Set());
      } catch (err) {
        setError(err.response?.data?.message || "Could not load students.");
      } finally {
        setBusy(false);
      }
    },
    [targetChosen, proctorParam, fYear, fSemester, fQuery],
  );

  const toggle = (rollNo) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(rollNo)) next.delete(rollNo);
      else next.add(rollNo);
      return next;
    });

  const claim = async () => {
    if (selected.size === 0) return;
    setBusy(true);
    setError("");
    try {
      const payload = { rollNos: [...selected] };
      if (proctorParam) payload.proctor = proctorParam;
      const res = await api.post("/admin/proctor/assignments", payload, {
        headers: getAdminHeaders(),
      });
      setResults(res.data);
      // refresh both panels: claimed rows flip to "yours" and the list updates
      await Promise.all([loadClaimable(pageInfo.number), loadAssigned()]);
    } catch (err) {
      setError(err.response?.data?.message || "Could not claim the selected students.");
    } finally {
      setBusy(false);
    }
  };

  const removeAssignment = async (rollNo) => {
    const who = isProctor ? "your supervision" : `${targetProctor}'s supervision`;
    if (!window.confirm(`Remove ${rollNo} from ${who}? The student account is not deleted.`)) return;
    setRemovingRoll(rollNo);
    setError("");
    try {
      await api.delete(`/admin/proctor/assignments/${rollNo}`, { headers: getAdminHeaders() });
      setAssigned((prev) => (prev || []).filter((s) => s.rollNo !== rollNo));
    } catch (err) {
      setError(err.response?.data?.message || "Could not remove the assignment.");
    } finally {
      setRemovingRoll("");
    }
  };

  return (
    <div className="flex flex-col gap-6">
      {/* ---- current assignments ---- */}
      <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
        <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-secondary-ink">
          <Users size={18} /> {isProctor ? "Students under your supervision" : "Assigned students"}
        </h2>
        <p className="mb-4 text-xs text-ink-muted">
          {isProctor
            ? "Removing a student only ends your supervision — it never deletes their account."
            : "Pick a proctor to view and manage their assigned students."}
        </p>

        {!isProctor && (
          <div className="mb-4 flex max-w-sm flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Proctor</label>
            <select
              className={inputClass}
              value={targetProctor}
              onChange={(e) => {
                setTargetProctor(e.target.value);
                setAssigned(null);
                setRows(null);
                setResults(null);
                setError("");
              }}
              data-cy="claim-proctor-select"
            >
              <option value="">Select proctor</option>
              {proctors.map((p) => (
                <option key={p.username} value={p.username}>
                  {p.username}
                  {p.departmentName ? ` (${p.departmentName})` : ""}
                </option>
              ))}
            </select>
            {proctors.length === 0 && (
              <p className="text-xs text-ink-muted">
                No proctor accounts yet — create one under Manage Users.
              </p>
            )}
          </div>
        )}

        <button
          type="button"
          onClick={loadAssigned}
          disabled={assignedBusy || !targetChosen}
          data-cy="assigned-load"
          className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary disabled:opacity-60"
        >
          {assignedBusy ? <LoaderCircle size={15} className="animate-spin" /> : <Users size={15} />}
          Load assigned students
        </button>

        {assigned && (
          <div className="mt-4">
            {assigned.length === 0 ? (
              <p
                className="rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm"
                data-cy="assigned-empty"
              >
                No students assigned yet — use the picker below to claim some.
              </p>
            ) : (
              <div className="overflow-x-auto rounded-2xl border border-stroke">
                <table className="min-w-full border-collapse text-left text-sm" data-cy="assigned-list">
                  <thead>
                    <tr className="bg-surface-muted text-xs uppercase tracking-[0.08em]">
                      <th className="px-4 py-3">USN</th>
                      <th className="px-4 py-3">Name</th>
                      <th className="px-4 py-3">Sem</th>
                      <th className="px-4 py-3">Assigned by</th>
                      <th className="px-4 py-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {assigned.map((s) => (
                      <tr key={s.rollNo} className="border-t border-stroke">
                        <td className="px-4 py-2.5 font-mono text-xs">{s.rollNo}</td>
                        <td className="px-4 py-2.5">{s.name || "—"}</td>
                        <td className="px-4 py-2.5">{s.currentSemester || "—"}</td>
                        <td className="px-4 py-2.5 text-xs text-ink-muted">
                          {s.assignedBy || "—"}
                        </td>
                        <td className="px-4 py-2.5 text-right">
                          <button
                            type="button"
                            onClick={() => removeAssignment(s.rollNo)}
                            disabled={removingRoll === s.rollNo}
                            data-cy={`assigned-remove-${s.rollNo}`}
                            className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50 disabled:opacity-60"
                          >
                            {removingRoll === s.rollNo ? (
                              <LoaderCircle size={13} className="animate-spin" />
                            ) : (
                              <UserMinus size={13} />
                            )}
                            Remove
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </section>

      {/* ---- claim picker ---- */}
      <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
        <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-secondary-ink">
          <UserCheck size={18} /> Claim students
        </h2>
        <p className="mb-4 text-xs text-ink-muted">
          Find existing students of {isProctor ? `your department${adminDepartment ? ` (${adminDepartment})` : ""}` : "the proctor's department"} by
          admission year and semester, then claim the selection. A student can have only one
          proctor — already-supervised students can't be selected.
        </p>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Admission year</label>
            <input
              className={inputClass}
              type="text"
              placeholder="e.g. 2024"
              value={fYear}
              onChange={(e) => setFYear(e.target.value)}
              data-cy="claim-year"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Semester</label>
            <select
              className={inputClass}
              value={fSemester}
              onChange={(e) => setFSemester(e.target.value)}
              data-cy="claim-sem"
            >
              <option value="">All</option>
              {/* a filter READS data — keep the full range so odd-semester rows stay findable */}
              {ALL_SEMESTERS.map((s) => (
                <option key={s} value={s}>
                  Semester {s}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">USN / name</label>
            <input
              className={inputClass}
              type="text"
              placeholder="search"
              value={fQuery}
              onChange={(e) => setFQuery(e.target.value)}
              data-cy="claim-query"
            />
          </div>
        </div>

        <div className="mt-4 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => {
              // a fresh search clears the previous claim summary — but the post-claim table
              // refresh (loadClaimable from claim()) must NOT
              setResults(null);
              loadClaimable(0);
            }}
            disabled={busy || !targetChosen}
            data-cy="claim-load"
            className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary disabled:opacity-60"
          >
            {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />}
            Find students
          </button>
          <button
            type="button"
            onClick={claim}
            disabled={busy || selected.size === 0}
            data-cy="claim-submit"
            className="inline-flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-semibold text-white disabled:opacity-60"
          >
            <Check size={15} /> Claim selected ({selected.size})
          </button>
        </div>

        {error && (
          <p className="mt-3 text-sm text-red-600" role="alert" data-cy="claim-error">
            {error}
          </p>
        )}

        {results && (
          <div
            className="mt-4 rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm"
            data-cy="claim-results"
          >
            <p className="font-semibold">
              Assigned {results.created} · skipped {results.skipped} · errors {results.errors}
            </p>
            {/* Same DTO as the bulk tabs, but only the failures are worth a line here, so this
                shares the rows decision and not the table. */}
            {batchRows(results)
              .filter((r) => r.status === "ERROR")
              .map((r) => (
                <p key={r.rollNo} className="mt-1 text-xs text-red-600">
                  {r.rollNo}: {r.message}
                </p>
              ))}
          </div>
        )}

        {rows && (
          <div className="mt-4">
            {rows.length === 0 ? (
              <p
                className="rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm"
                data-cy="claim-empty"
              >
                No students match these filters.
              </p>
            ) : (
              <>
                <div className="overflow-x-auto rounded-2xl border border-stroke">
                  <table className="min-w-full border-collapse text-left text-sm">
                    <thead>
                      <tr className="bg-surface-muted text-xs uppercase tracking-[0.08em]">
                        <th className="px-4 py-3" />
                        <th className="px-4 py-3">USN</th>
                        <th className="px-4 py-3">Name</th>
                        <th className="px-4 py-3">Sem</th>
                        <th className="px-4 py-3">Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((s) => {
                        const claimable = !s.proctored;
                        return (
                          <tr key={s.rollNo} className="border-t border-stroke">
                            <td className="px-4 py-2.5">
                              <input
                                type="checkbox"
                                checked={selected.has(s.rollNo)}
                                onChange={() => toggle(s.rollNo)}
                                disabled={!claimable}
                                aria-label={`Select ${s.rollNo}`}
                                data-cy={`claim-select-${s.rollNo}`}
                              />
                            </td>
                            <td className="px-4 py-2.5 font-mono text-xs">{s.rollNo}</td>
                            <td className="px-4 py-2.5">{s.name}</td>
                            <td className="px-4 py-2.5">{s.currentSemester}</td>
                            <td className="px-4 py-2.5">
                              {s.mine ? (
                                <span className="rounded-full bg-primary-tint px-2.5 py-0.5 text-xs font-semibold text-primary-ink">
                                  {isProctor ? "Yours" : "This proctor's"}
                                </span>
                              ) : s.proctored ? (
                                <span className="rounded-full bg-surface-muted px-2.5 py-0.5 text-xs font-semibold text-ink-muted">
                                  Has a proctor
                                </span>
                              ) : (
                                <span className="text-xs text-ink-muted">Unassigned</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                {pageInfo.totalPages > 1 && (
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
                    <span className="text-ink-muted">
                      Page {pageInfo.number + 1} of {pageInfo.totalPages} · {pageInfo.totalElements} students
                    </span>
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={() => loadClaimable(pageInfo.number - 1)}
                        disabled={busy || pageInfo.number <= 0}
                        data-cy="claim-prev"
                        className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-40"
                      >
                        <ChevronLeft size={13} /> Prev
                      </button>
                      <button
                        type="button"
                        onClick={() => loadClaimable(pageInfo.number + 1)}
                        disabled={busy || pageInfo.number >= pageInfo.totalPages - 1}
                        data-cy="claim-next"
                        className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-40"
                      >
                        Next <ChevronRight size={13} />
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

export default ClaimStudentsTab;
