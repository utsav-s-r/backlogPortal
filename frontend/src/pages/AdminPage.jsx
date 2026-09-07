import { useState, useEffect, useCallback, useRef } from "react";
import {
  ArrowLeft,
  BadgeCheck,
  Download,
  History,
  LoaderCircle,
  X,
  XCircle,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link } from "react-router-dom";
import AdminLayout from "../components/layout/AdminLayout";
import api from "../lib/api";
import { saveBlob, readBlobErrorMessage } from "../lib/download";
import { ROLE } from "../lib/roles";
import { useArmedConfirm } from "../hooks/useArmedConfirm";
import SummaryCards from "./admin/SummaryCards";
import RegistrationHistoryDialog from "./admin/RegistrationHistoryDialog";
import { useRegistrationHistory } from "./admin/useRegistrationHistory";
import { outcomeBadgeClass } from "./admin/outcomeBadge";
import { useRegistrationFilters } from "./admin/useRegistrationFilters";
import FilterPanel from "./admin/FilterPanel";
import SkipLink from "../components/ui/SkipLink";

const PAGE_SIZE = 25;

/** The subject dropdown's single value split into the two params the API takes. */
function splitSubjectToken(token) {
  if (!token) return {};
  if (token.startsWith("type:")) return { subjectType: token.slice(5) };
  return { subjectId: token };
}

function AdminPage() {
  const adminRole = sessionStorage.getItem("adminRole") || "";
  // Spelled as an INLINE literal of ROLE members, not `STAFF_ROLES.includes(adminRole)`, even
  // though that is the same set: this const feeds hook dependency arrays below, and react-hooks'
  // preserve-manual-memoization rule then errors ("Existing memoization could not be preserved")
  // whenever such a value comes from `.includes()` on a NAMED array. The full rule, and the three
  // formulations that also fail, are in lib/roles.js.
  // The named sets are still used below in JSX, where nothing memoizes and they are safe.
  const isAdmin = [
    ROLE.ADMIN,
    ROLE.PRINCIPAL,
    ROLE.HOD,
    ROLE.DEPT_OFFICE,
    ROLE.PROCTOR,
  ].includes(adminRole);
  // Dept-pinned roles can't widen scope — the server ignores the param for anyone pinned, so the
  // control would be a no-op. Direct comparison, not UNRESTRICTED.includes — same compiler rule as
  // isAdmin above; it reaches useRegistrationFilters, which gates the departments fetch on it.
  const canFilterByDepartment = adminRole === ROLE.ADMIN || adminRole === ROLE.PRINCIPAL;
  // A proctor sees a few dozen students in one dept; subject/semester/dept narrowing is noise there.
  const isProctor = adminRole === ROLE.PROCTOR;
  const [registrations, setRegistrations] = useState([]);
  const [loading, setLoading] = useState(true);
  // DEPT_PINNED's membership, inlined for the same lint reason as isAdmin — `filter` is a
  // dependency of fetchRegistrations.
  const [filter, setFilter] = useState(
    [ROLE.HOD, ROLE.DEPT_OFFICE, ROLE.PROCTOR].includes(adminRole) ? "SUBMITTED" : "ALL",
  );
  // server-side pagination: `page` is 0-based; pageInfo mirrors the Spring Page envelope
  const [page, setPage] = useState(0);
  const [pageInfo, setPageInfo] = useState({ totalPages: 0, totalElements: 0, number: 0 });
  // Stat-card counts come from the server (summary-counts), spanning the whole filtered set, not
  // just the loaded page
  const [counts, setCounts] = useState({ total: 0, submitted: 0, verified: 0, rejected: 0 });
  // Counts-fetch failure ⇒ cards read "—", not the initial zeros: four confident zeros above a table
  // full of rows claims "the queue is empty", the one thing they must never say on their own failure.
  // Stale pre-action numbers are just as wrong, so the post-action refetch failure routes here too.
  const [countsError, setCountsError] = useState("");
  const [verifyingRegId, setVerifyingRegId] = useState("");
  const [rejectingRegId, setRejectingRegId] = useState("");
  // two-step arm→confirm for rejecting an already-VERIFIED registration
  const rejectVerified = useArmedConfirm();
  const [rowErrors, setRowErrors] = useState({});
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState("");
  // Whole-list load failure (e.g. a 403 scope denial) — distinct from rowErrors, which are per-row
  // verify/reject failures.
  const [loadError, setLoadError] = useState("");

  // Rows ticked for export, by regId. Kept across pages so a selection can span them; cleared on any
  // filter change, since the ticked rows may no longer be in the result set.
  const [selectedIds, setSelectedIds] = useState(() => new Set());

  // Filters own the draft/applied split; the page owns paging and the export selection, so those
  // two resets are injected rather than reached into from the hook.
  const filters = useRegistrationFilters({
    isAdmin,
    canFilterByDepartment,
    onApplied: () => {
      setPage(0);
      setSelectedIds(new Set());
    },
  });
  const { appliedFilters, cyclesStatus } = filters;

  // Audit history modal. The cache lives in the hook, called HERE rather than inside the dialog,
  // so it survives close/reopen — see useRegistrationHistory.
  const history = useRegistrationHistory();

  // Monotonic counter shared by every registrations fetch (the filter effect and the imperative
  // post-action resync). Each captures the next value and applies its response only if still latest,
  // so out-of-order completions from rapid filter changes can't clobber the table.
  const registrationsReqRef = useRef(0);
  // In-flight controllers: each fetch aborts its predecessor — whose response the seq guard would
  // drop anyway — freeing the backend connection early.
  const registrationsAbortRef = useRef(null);
  const countsAbortRef = useRef(null);

  // shared params (everything but status/page) for the list and counts endpoints, so the cards
  // and the table stay on the same filtered set
  const appendFilterParams = useCallback((params) => {
    const { subjectId, subjectType } = splitSubjectToken(appliedFilters.subject);
    if (subjectId) params.append("subjectId", subjectId);
    if (subjectType) params.append("subjectType", subjectType);
    if (appliedFilters.searchQuery) params.append("searchQuery", appliedFilters.searchQuery);
    if (appliedFilters.semester) params.append("semester", appliedFilters.semester);
    if (appliedFilters.departmentId) params.append("departmentId", appliedFilters.departmentId);
    if (appliedFilters.examCycleId) params.append("examCycleId", appliedFilters.examCycleId);
  }, [appliedFilters]);

  const fetchRegistrations = useCallback(() => {
    if (!isAdmin) return Promise.resolve();
    // tag the request; only the latest may apply its result
    const seq = ++registrationsReqRef.current;
    registrationsAbortRef.current?.abort();
    const controller = new AbortController();
    registrationsAbortRef.current = controller;
    const params = new URLSearchParams();
    appendFilterParams(params);
    // status filtering is server-side — a page only holds part of the result
    if (filter !== "ALL") params.append("status", filter);
    params.append("page", String(page));
    params.append("size", String(PAGE_SIZE));

    return api
      .get(`/admin/registrations?${params.toString()}`, {
        signal: controller.signal,
      })
      .then((res) => {
        if (seq !== registrationsReqRef.current) return; // superseded
        setLoadError("");
        setRegistrations(res.data.content || []);
        setPageInfo({
          totalPages: res.data.totalPages ?? 0,
          totalElements: res.data.totalElements ?? 0,
          number: res.data.number ?? 0,
        });
        setLoading(false);
      })
      .catch((error) => {
        if (error.code === "ERR_CANCELED") return; // superseded request aborted
        console.error("Failed to fetch dashboard data:", error);
        // 401 is the api.js interceptor's job (it signs out); handling it here too would race that
        // redirect. Everything else, 403 included, is shown in place: a scope denial must not eject an admin
        // mid-task, and the server's reason is the actionable part.
        if (error.response?.status === 401) return;
        if (seq !== registrationsReqRef.current) return; // superseded
        setLoadError(
          error.response?.data?.message ||
            "Could not load registrations. Please try again.",
        );
        setLoading(false); // otherwise the spinner outlives the failure
      });
  }, [isAdmin, appendFilterParams, filter, page]);

  const fetchCounts = useCallback(() => {
    if (!isAdmin) return Promise.resolve();
    countsAbortRef.current?.abort();
    const controller = new AbortController();
    countsAbortRef.current = controller;
    const params = new URLSearchParams();
    appendFilterParams(params); // no status: cards span all statuses of the filtered set
    return api
      .get(`/admin/registrations/summary-counts?${params.toString()}`, {
        signal: controller.signal,
      })
      .then((res) => {
        setCountsError("");
        setCounts(res.data);
      })
      .catch((err) => {
        if (err.code === "ERR_CANCELED") return; // superseded request aborted
        console.error("Failed to fetch summary counts", err);
        if (err.response?.status === 401) return; // the interceptor is signing out
        setCountsError(
          err.response?.data?.message || "Could not load the totals. Please refresh.",
        );
      });
  }, [isAdmin, appendFilterParams]);

  useEffect(() => {
    fetchRegistrations();
  }, [fetchRegistrations]);

  useEffect(() => {
    fetchCounts();
  }, [fetchCounts]);

  // leaving the page cancels whatever is still in flight
  useEffect(
    () => () => {
      registrationsAbortRef.current?.abort();
      countsAbortRef.current?.abort();
    },
    [],
  );

  // Returns `prev` untouched when there is no error for this row, so React can bail out of the
  // re-render. (This was a generic `dropRegId(setter, regId)` while the history cache shared it;
  // that cache now lives in useRegistrationHistory, which has its own `invalidate`.)
  const clearRowError = (regId) =>
    setRowErrors((prev) => {
      if (!prev[regId]) return prev;
      const next = { ...prev };
      delete next[regId];
      return next;
    });

  // Verify/reject appends an event and moves the row, so the cached trail is stale and the table
  // and stat cards both need refetching. Every post-action path goes through here.
  const resyncAfterAction = async (regId) => {
    history.invalidate(regId);
    await Promise.all([fetchRegistrations(), fetchCounts()]);
  };

  // Verify/reject failures land here. Rows mutate only on success, so there is nothing to roll
  // back — surface an inline per-row error instead. When the server says the row moved underneath
  // us (404 gone, 409/410 conflict: another admin actioned it, or the cycle closed), refetch so
  // the table shows true server state rather than a stale row.
  const handleActionError = async (regId, err, fallback) => {
    console.error(err);
    const status = err.response?.status;
    setRowErrors((prev) => ({
      ...prev,
      [regId]: err.response?.data?.message || fallback,
    }));
    if (status === 404 || status === 409 || status === 410) {
      await resyncAfterAction(regId);
    }
  };

  const handleVerify = async (regId) => {
    clearRowError(regId);
    setVerifyingRegId(regId);
    try {
      await api.put(`/register/verify/${regId}`, { action: "VERIFIED" });
      // refetch: under server-side status filtering the row may leave the current page
      await resyncAfterAction(regId);
    } catch (err) {
      await handleActionError(regId, err, "Failed to verify. Please refresh and try again.");
    } finally {
      setVerifyingRegId("");
    }
  };

  const handleReject = async (regId) => {
    clearRowError(regId);
    setRejectingRegId(regId);
    try {
      await api.put(`/register/verify/${regId}`, { action: "REJECTED" });
      await resyncAfterAction(regId);
    } catch (err) {
      await handleActionError(regId, err, "Failed to reject. Please refresh and try again.");
    } finally {
      setRejectingRegId("");
      rejectVerified.disarm();
    }
  };

  // What the export covers. Ticked rows win outright; otherwise it mirrors exactly what the table
  // is showing — the cycle and the status tab included, never a fixed "active cycle, verified only"
  // narrowing that ignores what is on screen.
  const buildExportBody = () => {
    if (selectedIds.size > 0) {
      return { regIds: [...selectedIds] };
    }
    const body = {};
    const { subjectId, subjectType } = splitSubjectToken(appliedFilters.subject);
    if (subjectId) body.subjectId = Number(subjectId);
    if (subjectType) body.subjectType = subjectType;
    if (appliedFilters.searchQuery) body.searchQuery = appliedFilters.searchQuery;
    if (appliedFilters.semester) body.semester = Number(appliedFilters.semester);
    if (appliedFilters.departmentId) body.departmentId = Number(appliedFilters.departmentId);
    if (appliedFilters.examCycleId) {
      body.examCycleId = Number(appliedFilters.examCycleId);
    } else if (cyclesStatus === "loaded") {
      // Only an INFORMED empty selection means "every cycle".
      body.allCycles = true;
    } else {
      // The cycle list never loaded, so the scope is unknown rather than deliberately "all".
      // Refusing HERE, where the body is built, keeps "what scope does this export have?" in one
      // function — a second caller can't miss a guard that lives in the first caller.
      return null;
    }
    body.status = filter; // "ALL" included — the server maps it to every status
    return body;
  };

  // Only claim failure once it has actually failed — "loading" is not "error", or this banner
  // renders on every page load while the fetch is still in flight.
  const showScopeWarning = cyclesStatus === "error" && !appliedFilters.examCycleId;

  const handleExportPdf = () => {
    const body = buildExportBody();
    if (!body) {
      setExportError(
        "Exam cycles could not be loaded, so this export's scope is unknown — it would span every cycle. Refresh the page, or tick the rows you want.",
      );
      return;
    }
    setIsExporting(true);
    setExportError("");

    api
      .post("/admin/export-pdf", body, {
        responseType: "blob", // required for file downloads
      })
      .then((res) => {
        // a 200 that isn't a PDF (e.g. an HTML error page) must not be saved as one
        const contentType = res.headers["content-type"] || "";
        if (!contentType.includes("application/pdf")) {
          throw new Error(`Unexpected export content type: ${contentType}`);
        }
        // saveBlob, not a hand-rolled anchor: it delays revokeObjectURL (iOS Safari consumes
        // the blob URL asynchronously, so revoking right after click() cancels the download) and
        // falls back to opening the blob where `download` is unsupported. Both were silent no-ops
        // here — the spinner cleared, no error, no file.
        saveBlob(res.data, `registrations-summary-${Date.now()}.pdf`, "application/pdf");
      })
      .catch(async (err) => {
        console.error("Failed to export PDF", err);
        setExportError(
          await readBlobErrorMessage(err, "Failed to export PDF. Please try again."),
        );
      })
      .finally(() => {
        setIsExporting(false);
      });
  };

  // The table shows exactly the current server page: filtering and paging are server-side, so
  // there is no client-side slicing.

  // ---- export selection ----
  const pageIds = registrations.map((r) => r.regId);
  const allOnPageSelected =
    pageIds.length > 0 && pageIds.every((id) => selectedIds.has(id));

  const toggleRow = (regId) =>
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (!next.delete(regId)) next.add(regId);
      return next;
    });

  // Adds or removes only THIS page's rows, leaving a selection made on other pages intact —
  // otherwise paging away would silently discard it.
  const togglePage = () =>
    setSelectedIds((prev) => {
      const next = new Set(prev);
      pageIds.forEach((id) => (allOnPageSelected ? next.delete(id) : next.add(id)));
      return next;
    });

  // The one page that renders a terminal denial rather than calling useRoleGuard: /admin IS that
  // hook's redirect target, so redirecting here would loop. Reachable only for an absent or
  // unrecognised adminRole, where "Go to Admin Login" is the right destination.
  // ROLE only — never re-add an `adminToken` check here or on the fetches. ProtectedAdminRoute
  // wraps /admin and redirects when the marker is absent, so this body cannot run without one; and
  // clearAdminSession drops adminRole too, covering the 401-to-navigation window.
  if (!isAdmin) {
    return (
      <div className="min-h-screen bg-surface-1 px-4 py-10 sm:px-6 lg:px-8">
        <div className="mx-auto w-full max-w-2xl py-8 text-center">
          <h1 className="mb-2 text-3xl font-semibold text-secondary-ink">
            Access Denied
          </h1>
          <p className="mb-5 text-ink">
            You must login as an admin to view this page.
          </p>
          <Link
            to="/admin/login"
            className="inline-flex items-center justify-center rounded-full bg-cta px-5 py-3 text-sm font-semibold text-cta-text"
          >
            Go to Admin Login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <AdminLayout>
      <SkipLink href="#admin-main">Skip to admin table</SkipLink>

      <div id="admin-main" className="mx-auto w-full max-w-7xl px-4 py-8 sm:px-6 lg:px-8">

        <SummaryCards counts={counts} error={countsError} />

        <FilterPanel
          isProctor={isProctor}
          canFilterByDepartment={canFilterByDepartment}
          {...filters.panel}
        />

        <section>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-4">
            <div className="flex flex-wrap gap-2">
              {["ALL", "SUBMITTED", "VERIFIED", "REJECTED"].map((f) => (
                <button
                  type="button"
                  key={f}
                  onClick={() => {
                    setPage(0); // switching status tab restarts at the first page
                    setFilter(f);
                  }}
                  className={`rounded-lg px-4 py-2 text-xs font-semibold tracking-[0.06em] transition-transform duration-200 hover:scale-105 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:ring-offset-2 focus-visible:ring-offset-surface-1 ${
                    filter === f
                      ? "bg-primary text-white"
                      : "bg-surface-muted text-ink hover:bg-primary-tint"
                  }`}
                  data-cy={`admin-filter-${f.toLowerCase()}`}
                >
                  {f}
                </button>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-2">
              {selectedIds.size > 0 && (
                <button
                  type="button"
                  onClick={() => setSelectedIds(new Set())}
                  data-cy="admin-selection-clear"
                  className="inline-flex items-center gap-1.5 rounded-lg bg-surface-muted px-3 py-2 text-xs font-semibold text-secondary-ink transition-colors hover:bg-primary-tint"
                >
                  <X size={13} /> Clear {selectedIds.size} selected
                </button>
              )}
              <button
                type="button"
                onClick={handleExportPdf}
                disabled={isExporting}
                data-cy="admin-export-pdf"
                className="inline-flex items-center gap-1.5 rounded-lg bg-secondary px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-primary disabled:opacity-50"
              >
                {isExporting ? (
                  <LoaderCircle size={14} className="animate-spin" />
                ) : (
                  <Download size={14} />
                )}
                {selectedIds.size > 0
                  ? `Export ${selectedIds.size} selected`
                  : "Export PDF"}
              </button>
            </div>
          </div>

          {/* Say what the button will actually produce — the set is not always what's on screen */}
          <p className="mb-3 text-xs text-ink-muted" data-cy="admin-export-scope">
            {selectedIds.size > 0
              ? `Exporting the ${selectedIds.size} ticked ${
                  selectedIds.size === 1 ? "row" : "rows"
                }, ignoring the filters.`
              : `Exporting every ${
                  filter === "ALL" ? "" : `${filter.toLowerCase()} `
                }registration matching the current filters.`}
          </p>

          {exportError && (
            <AlertBanner
              tone="error"
              role="alert"
              data-cy="admin-export-error"
              className="mb-3"
            >
              {exportError}
            </AlertBanner>
          )}

          {showScopeWarning && (
            <AlertBanner
              tone="warning"
              role="status"
              data-cy="admin-scope-warning"
              className="mb-3"
            >
              Not scoped to an exam cycle — the cycle list failed to load, so this table spans every
              cycle, including closed ones. Refresh before actioning anything.
            </AlertBanner>
          )}

          {loadError && (
            <AlertBanner
              tone="error"
              role="alert"
              data-cy="admin-load-error"
              className="mb-3"
            >
              {loadError}
            </AlertBanner>
          )}

          {loading ? (
            <p className="inline-flex items-center gap-2 rounded-lg bg-surface-muted px-4 py-3 text-sm">
              <LoaderCircle size={16} className="animate-spin" /> Loading
              registrations...
            </p>
          ) : registrations.length === 0 ? (
            // Says the query came back empty. Without it the page renders a column header over
            // nothing, which reads as a broken fetch — and the de-boxing removed the wrapper that
            // used to make an empty table look like an empty box. Wording mirrors the export-scope
            // line above so the two never disagree about what is being looked at.
            <p
              className="rounded-lg bg-surface-muted px-4 py-3 text-sm text-ink"
              data-cy="admin-empty"
            >
              {`No ${
                filter === "ALL" ? "" : `${filter.toLowerCase()} `
              }registrations match the current filters.`}
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full border-collapse text-left text-sm">
                <thead>
                  <tr className="border-b border-stroke text-xs uppercase tracking-[0.08em] text-ink-muted">
                    <th className="px-4 py-3">
                      <input
                        type="checkbox"
                        checked={allOnPageSelected}
                        onChange={togglePage}
                        disabled={pageIds.length === 0}
                        aria-label="Select all rows on this page"
                        data-cy="admin-select-page"
                        className="h-4 w-4 accent-primary"
                      />
                    </th>
                    <th className="px-4 py-3">USN</th>
                    <th className="px-4 py-3">Name</th>
                    <th className="px-4 py-3">Sem</th>
                    <th className="px-4 py-3">Cycle</th>
                    <th className="px-4 py-3">Subjects</th>
                    <th className="px-4 py-3">Status</th>
                    <th className="px-4 py-3">Date</th>
                    <th className="px-4 py-3">Acted By</th>
                    <th className="px-4 py-3">Action</th>
                    <th className="px-4 py-3">History</th>
                  </tr>
                </thead>
                <tbody>
                  {registrations.map((reg) => (
                    <tr
                      key={reg.regId}
                      className="border-t border-stroke align-top"
                    >
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selectedIds.has(reg.regId)}
                          onChange={() => toggleRow(reg.regId)}
                          aria-label={`Select registration for ${reg.rollNo}`}
                          data-cy={`admin-select-${reg.regId}`}
                          className="h-4 w-4 accent-primary"
                        />
                      </td>
                      <td className="px-4 py-3 text-ink">
                        {reg.rollNo}
                      </td>
                      <td className="px-4 py-3 text-ink">
                        {reg.studentName}
                      </td>
                      <td className="px-4 py-3">{reg.semester}</td>
                      <td className="px-4 py-3">
                        {reg.examCycle ? (
                          <span className="text-xs text-ink">
                            {reg.examCycle}
                          </span>
                        ) : (
                          <span className="text-xs text-ink-muted">—</span>
                        )}
                      </td>
                      <td className="px-4 py-3">{reg.subjects.join(", ")}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`rounded-full px-3 py-1 text-xs font-semibold ${outcomeBadgeClass(
                            reg.status,
                            "bg-surface-muted",
                          )}`}
                        >
                          {reg.status}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {new Date(reg.registeredAt).toLocaleDateString()}
                      </td>
                      <td className="px-4 py-3">
                        {reg.verifiedBy ? (
                          <span className="text-xs text-ink">
                            {reg.verifiedBy}
                          </span>
                        ) : (
                          <span className="text-xs text-ink-muted">—</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        {reg.status === "SUBMITTED" && adminRole !== ROLE.PRINCIPAL ? (
                          <div className="flex flex-col gap-1.5">
                            <div className="flex flex-col items-start gap-1.5">
                              <button
                                type="button"
                                onClick={() => handleVerify(reg.regId)}
                                disabled={verifyingRegId === reg.regId || rejectingRegId === reg.regId}
                                data-cy="admin-verify"
                                className="inline-flex w-15 items-center justify-center gap-1 rounded-lg bg-primary py-1 text-xs font-semibold text-white transition-colors hover:opacity-90 disabled:opacity-50"
                              >
                                {verifyingRegId === reg.regId ? (
                                  <LoaderCircle size={13} className="animate-spin" />
                                ) : (
                                  <BadgeCheck size={13} />
                                )}
                                Verify
                              </button>
                              <button
                                type="button"
                                onClick={() => handleReject(reg.regId)}
                                disabled={rejectingRegId === reg.regId || verifyingRegId === reg.regId}
                                data-cy="admin-reject"
                                className="inline-flex w-15 items-center justify-center gap-1 rounded-lg bg-red-50 py-1 text-xs font-semibold text-red-600 transition-colors hover:bg-red-100 disabled:opacity-50"
                              >
                                {rejectingRegId === reg.regId ? (
                                  <LoaderCircle size={14} className="animate-spin" />
                                ) : (
                                  <XCircle size={14} />
                                )}
                                Reject
                              </button>
                            </div>
                            {rowErrors[reg.regId] && (
                              <p
                                className="text-sm font-medium text-red-600"
                                role="alert"
                                data-cy="admin-action-error"
                              >
                                {rowErrors[reg.regId]}
                              </p>
                            )}
                          </div>
                        ) : reg.status === "VERIFIED" ? (
                          <div className="flex flex-col gap-1.5">
                            {rejectVerified.isArmed(reg.regId) ? (
                              <div className="flex flex-col items-start gap-1.5">
                                <button
                                  type="button"
                                  onClick={() => handleReject(reg.regId)}
                                  disabled={rejectingRegId === reg.regId}
                                  data-cy="admin-reject-verified-confirm"
                                  className="inline-flex w-14 items-center justify-center rounded-lg bg-red-600 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-50"
                                >
                                  {rejectingRegId === reg.regId ? (
                                    <LoaderCircle size={14} className="animate-spin" />
                                  ) : (
                                    "Confirm"
                                  )}
                                </button>
                                <button
                                  type="button"
                                  onClick={() => rejectVerified.disarm()}
                                  disabled={rejectingRegId === reg.regId}
                                  data-cy="admin-reject-verified-cancel"
                                  className="inline-flex w-14 items-center justify-center rounded-lg bg-surface-muted py-1.5 text-xs font-semibold text-ink transition-colors hover:bg-primary-tint disabled:opacity-50"
                                >
                                  Cancel
                                </button>
                              </div>
                            ) : (
                              <div className="flex flex-col items-start gap-1">
                                <span className="text-sm font-semibold text-primary-ink">
                                  Verified
                                </span>
                                {adminRole !== ROLE.PRINCIPAL ? (
                                  <button
                                    type="button"
                                    onClick={() => {
                                      clearRowError(reg.regId);
                                      rejectVerified.arm(reg.regId);
                                    }}
                                    data-cy="admin-reject-verified"
                                    className="inline-flex w-14 items-center justify-center rounded-lg bg-red-50 py-1.5 text-xs font-semibold text-red-600 transition-colors hover:bg-red-100"
                                  >
                                    Reject
                                  </button>
                                ) : null}
                              </div>
                            )}
                            {rowErrors[reg.regId] && (
                              <p
                                className="text-sm font-medium text-red-600"
                                role="alert"
                                data-cy="admin-action-error"
                              >
                                {rowErrors[reg.regId]}
                              </p>
                            )}
                          </div>
                        ) : reg.status === "REJECTED" ? (
                          <span className="text-sm font-semibold text-red-600">
                            Rejected
                          </span>
                        ) : (
                          <span className="text-sm font-semibold text-ink-muted">
                            Pending Verification
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <button
                          type="button"
                          onClick={() => history.open(reg.regId)}
                          data-cy={`history-${reg.regId}`}
                          className="inline-flex items-center gap-1 rounded-lg bg-surface-muted px-3 py-1.5 text-xs font-semibold text-secondary-ink transition-colors hover:bg-primary-tint"
                        >
                          <History size={14} /> View
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Not components/ui/Pager: this presentation differs — no bordered box, the page-info
            line sits outside the button row, rotated arrows rather than chevrons. Folding it in
            would need a variant prop serving one caller. */}
        {!loading && pageInfo.totalPages > 1 && (
            <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
              <p
                className="text-xs text-ink-muted"
                data-cy="admin-page-info"
              >
                Page {pageInfo.number + 1} of {pageInfo.totalPages} ·{" "}
                {pageInfo.totalElements} total
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page <= 0}
                  data-cy="admin-page-prev"
                  className="inline-flex items-center gap-1 rounded-lg bg-surface-muted px-3 py-1.5 text-xs font-semibold text-secondary-ink transition-colors hover:bg-primary-tint disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <ArrowLeft size={14} /> Prev
                </button>
                <button
                  type="button"
                  onClick={() => setPage((p) => Math.min(pageInfo.totalPages - 1, p + 1))}
                  disabled={page >= pageInfo.totalPages - 1}
                  data-cy="admin-page-next"
                  className="inline-flex items-center gap-1 rounded-lg bg-surface-muted px-3 py-1.5 text-xs font-semibold text-secondary-ink transition-colors hover:bg-primary-tint disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Next <ArrowLeft size={14} className="rotate-180" />
                </button>
              </div>
            </div>
          )}
        </section>
      </div>

      {history.regId && (
        <RegistrationHistoryDialog
          events={history.events}
          loading={history.loading}
          error={history.error}
          onClose={history.close}
        />
      )}
    </AdminLayout>
  );
}

export default AdminPage;
