import { useState, useEffect, useCallback, useRef } from "react";
import {
  ArrowLeft,
  BadgeCheck,
  BookOpen,
  Building2,
  CalendarRange,
  CircleDashed,
  Download,
  History,
  IdCard,
  KeyRound,
  LoaderCircle,
  LogOut,
  Search,
  Shield,
  Users,
  X,
  XCircle,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link, useNavigate } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";
import ThemeToggle from "../components/ui/ThemeToggle";
import api, { getAdminHeaders, logoutAdmin } from "../lib/api";
import { saveBlob, readBlobErrorMessage } from "../lib/download";
import { reportLoadError } from "../lib/loadError";

const PAGE_SIZE = 25;
// History dialog's Tab-trap descendants. [tabindex="-1"] excluded on purpose: it means
// script-focusable, not Tab-focusable.
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
// 1..8 is the programme, matching Semesters.java on the server
const SEMESTERS = [1, 2, 3, 4, 5, 6, 7, 8];

/** VERIFIED/REJECTED/other pill classes, shared by the table rows and the history modal. Only the
 *  neutral fallback differs (row sits on the card, event on a muted panel), so the caller passes it
 *  rather than the two copies drifting apart. */
function outcomeBadgeClass(outcome, neutralBg) {
  if (outcome === "VERIFIED") return "bg-primary-tint text-primary-ink";
  if (outcome === "REJECTED") return "bg-red-50 text-red-600";
  return `${neutralBg} text-secondary-ink`;
}

/** The subject dropdown's single value split into the two params the API takes. */
function splitSubjectToken(token) {
  if (!token) return {};
  if (token.startsWith("type:")) return { subjectType: token.slice(5) };
  return { subjectId: token };
}

function AdminPage() {
  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";
  const navigate = useNavigate();
  const isAdmin = ["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR"].includes(
    adminRole,
  );
  const adminToken = sessionStorage.getItem("adminToken");
  // Dept-pinned roles can't widen scope — the server ignores the param for anyone pinned, so the
  // control would be a no-op.
  const canFilterByDepartment = adminRole === "ADMIN" || adminRole === "PRINCIPAL";
  // A proctor sees a few dozen students in one dept; subject/semester/dept narrowing is noise there.
  const isProctor = adminRole === "PROCTOR";
  const [registrations, setRegistrations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState(
    ["HOD", "DEPT_OFFICE", "PROCTOR"].includes(adminRole) ? "SUBMITTED" : "ALL",
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
  const [confirmRejectVerifiedId, setConfirmRejectVerifiedId] = useState("");
  const [rowErrors, setRowErrors] = useState({});
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState("");
  // Whole-list load failure (e.g. a 403 scope denial) — distinct from rowErrors, which are per-row
  // verify/reject failures.
  const [loadError, setLoadError] = useState("");

  // Rows ticked for export, by regId. Kept across pages so a selection can span them; cleared on any
  // filter change, since the ticked rows may no longer be in the result set.
  const [selectedIds, setSelectedIds] = useState(() => new Set());

  // Filter states
  const [allSubjects, setAllSubjects] = useState([]);
  const [loadingSubjects, setLoadingSubjects] = useState(true);
  // One control for both subject axes: "" = all, "type:REGULAR"/"type:ELECTIVE" = a whole type, else
  // a subject id. subjectId already determines subjectType, so two controls meant hand-syncing them.
  const [subjectFilter, setSubjectFilter] = useState("");
  const [semesterFilter, setSemesterFilter] = useState("");
  const [deptFilter, setDeptFilter] = useState("");
  const [departments, setDepartments] = useState([]);
  // Raw text-box DRAFT, updated per keystroke; nothing fetches off it — it reaches the server only
  // via appliedFilters on Apply
  const [searchInput, setSearchInput] = useState("");
  const [examCycles, setExamCycles] = useState([]);
  const [cycleFilter, setCycleFilter] = useState("");
  // Splits the two meanings an empty `examCycleId` carried: "user chose All Cycles" vs "we never
  // learned what the cycles are". Unsplit, a failed fetch silently listed every cycle including
  // closed ones and the PDF export followed via allCycles — the one-value-two-meanings shape of the
  // 2026-08-11 auth fail-open. THREE states, not a boolean: pending must be distinguishable from
  // failed, or the "cycles failed to load" banner fires on every page load while the request is
  // still in flight, and a banner that cries wolf is ignored on the day it's true. "loaded" covers a
  // successful fetch that found no ACTIVE cycle — a real answer, not an unknown one.
  const [cyclesStatus, setCyclesStatus] = useState("loading"); // "loading" | "loaded" | "error"
  const [cyclesError, setCyclesError] = useState("");
  // Same trap for the other two filter dropdowns: an empty list reads as "none are configured", so a
  // failed fetch needs a slot of its own to say otherwise.
  const [subjectsError, setSubjectsError] = useState("");
  const [departmentsError, setDepartmentsError] = useState("");

  // The states above are the DRAFT being edited; the registrations fetch keys off appliedFilters, so
  // the table updates only on Apply, never mid-edit on a half-built combo.
  const [appliedFilters, setAppliedFilters] = useState({
    subject: "",
    searchQuery: "",
    semester: "",
    departmentId: "",
    examCycleId: "",
  });

  // Audit history modal. registration_events is append-only and this admin is the only writer
  // reachable from this screen, so a fetched trail stays valid until we verify/reject that row —
  // cache per regId (invalidated below) and reopen instantly instead of re-paying the fetch.
  const [historyRegId, setHistoryRegId] = useState("");
  const [historyCache, setHistoryCache] = useState({});
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState("");
  // regId the in-flight events request belongs to, so a superseded response can't clear the spinner
  // or post an error over a row the admin has since switched to.
  const historyReqRef = useRef("");
  // Modal focus bookkeeping: the close button to move focus INTO the dialog, and the opener so focus
  // returns there rather than to the top of the document.
  const historyCloseRef = useRef(null);
  const historyTriggerRef = useRef(null);
  const historyDialogRef = useRef(null); // the trap needs the dialog's focusable descendants
  const historyEvents = historyCache[historyRegId] || [];

  const closeHistory = () => {
    setHistoryRegId("");
    historyTriggerRef.current?.focus(); // back to the History button that opened it
  };

  // Monotonic counter shared by every registrations fetch (the filter effect and the imperative
  // post-action resync). Each captures the next value and applies its response only if still latest,
  // so out-of-order completions from rapid filter changes can't clobber the table.
  const registrationsReqRef = useRef(0);
  // In-flight controllers: each fetch aborts its predecessor — whose response the seq guard would
  // drop anyway — freeing the backend connection early.
  const registrationsAbortRef = useRef(null);
  const countsAbortRef = useRef(null);

  // Subject-dropdown options follow the APPLIED filters like the table, not the draft: editing fires
  // zero requests until Apply instead of a DISTINCT-join query per keystroke. Deps are the endpoint's
  // four fields, not the whole appliedFilters object, so an Apply changing only the exam cycle won't
  // re-fetch identical options. A narrowed list never auto-clears the current selection — applying a
  // now-unlisted subject just yields no rows.
  useEffect(() => {
    if (!isAdmin || !adminToken) {
      return;
    }

    // `ignore` drops a stale response landing after a newer filter change; the AbortController goes
    // further and cancels the superseded request, freeing its backend connection
    let ignore = false;
    const controller = new AbortController();
    // NOTE: react-hooks/set-state-in-effect flags this setState. Intended and correct — a
    // leading loading flag for an API fetch, exactly the "synchronize with an external system"
    // case the rule carves out. A knowing lint error, deliberately not disabled.
    setLoadingSubjects(true);
    const subjectParams = new URLSearchParams();
    if (appliedFilters.searchQuery) subjectParams.append("searchQuery", appliedFilters.searchQuery);
    if (appliedFilters.semester) subjectParams.append("semester", appliedFilters.semester);
    if (appliedFilters.departmentId) subjectParams.append("departmentId", appliedFilters.departmentId);

    api
      .get(`/admin/subjects-for-filter?${subjectParams.toString()}`, {
        headers: getAdminHeaders(),
        signal: controller.signal,
      })
      .then((res) => {
        if (ignore) return;
        setAllSubjects(res.data);
        setSubjectsError("");
      })
      .catch((err) => {
        if (ignore) return;
        console.error("Failed to fetch subjects list for filter", err);
        setAllSubjects([]);
        reportLoadError(err, setSubjectsError, "Could not load the subject list. Please refresh.");
      })
      .finally(() => {
        if (!ignore) setLoadingSubjects(false);
      });

    return () => {
      ignore = true;
      controller.abort();
    };
  }, [
    isAdmin,
    adminToken,
    appliedFilters.searchQuery,
    appliedFilters.semester,
    appliedFilters.departmentId,
  ]);

  // Department options for the ADMIN/PRINCIPAL department filter. Everyone else is dept-pinned,
  // so there is nothing to choose and nothing to fetch.
  useEffect(() => {
    if (!isAdmin || !adminToken || !canFilterByDepartment) return;
    let ignore = false;
    const controller = new AbortController();
    api
      .get("/admin/departments", { headers: getAdminHeaders(), signal: controller.signal })
      .then((res) => {
        if (ignore) return;
        setDepartments(Array.isArray(res.data) ? res.data : []);
        setDepartmentsError("");
      })
      .catch((err) => {
        if (ignore) return;
        // an empty dropdown reads as "no departments exist" — say what actually happened
        console.error("Failed to fetch departments for filter", err);
        setDepartments([]);
        reportLoadError(err, setDepartmentsError, "Could not load departments. Please refresh.");
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [isAdmin, adminToken, canFilterByDepartment]);

  useEffect(() => {
    if (!isAdmin || !adminToken) return;
    let ignore = false;
    const controller = new AbortController();
    api
      .get("/admin/exam-cycles", { headers: getAdminHeaders(), signal: controller.signal })
      .then((res) => {
        if (ignore) return;
        setExamCycles(res.data);
        setCyclesStatus("loaded");
        setCyclesError("");
        // default to the active cycle so the list and PDF export both scope to it;
        // "All Cycles" stays an explicit opt-in
        const active = Array.isArray(res.data) ? res.data.find((c) => c.active) : null;
        if (active) {
          // seed both draft and applied so the page auto-loads the active cycle
          setCycleFilter(String(active.id));
          setAppliedFilters((prev) => ({ ...prev, examCycleId: String(active.id) }));
        }
      })
      .catch((err) => {
        if (ignore || err.code === "ERR_CANCELED") return;
        console.error("Failed to fetch exam cycles", err);
        setExamCycles([]);
        if (err.response?.status === 401) return; // the interceptor is signing out
        // "error", never "loaded": the scope is unknown, not "all cycles"
        setCyclesStatus("error");
        setCyclesError(
          err.response?.data?.message || "Could not load exam cycles. Please refresh.",
        );
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [isAdmin, adminToken]);

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
    if (!isAdmin || !adminToken) return Promise.resolve();
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
        headers: getAdminHeaders(),
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
  }, [isAdmin, adminToken, appendFilterParams, filter, page]);

  const fetchCounts = useCallback(() => {
    if (!isAdmin || !adminToken) return Promise.resolve();
    countsAbortRef.current?.abort();
    const controller = new AbortController();
    countsAbortRef.current = controller;
    const params = new URLSearchParams();
    appendFilterParams(params); // no status: cards span all statuses of the filtered set
    return api
      .get(`/admin/registrations/summary-counts?${params.toString()}`, {
        headers: getAdminHeaders(),
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
  }, [isAdmin, adminToken, appendFilterParams]);

  useEffect(() => {
    fetchRegistrations();
  }, [fetchRegistrations]);

  useEffect(() => {
    fetchCounts();
  }, [fetchCounts]);

  // Escape closes the modal, focus moves into it on open, and Tab is trapped inside it.
  //
  // The trap is not polish: aria-modal="true" TELLS assistive tech the rest of the page is inert,
  // so letting Tab reach it makes the markup a lie — a keyboard or screen-reader user lands on
  // content that, as far as they've been told, isn't there.
  //
  // closeHistory is deliberately NOT in the deps: it's a plain function, so it changes identity
  // every render and would re-register the listener each time. It only sets state and focuses a
  // ref, so a stale closure is harmless.
  useEffect(() => {
    if (!historyRegId) return undefined;
    const onKeyDown = (e) => {
      if (e.key === "Escape") {
        closeHistory();
        return;
      }
      if (e.key !== "Tab") return;
      const dialog = historyDialogRef.current;
      if (!dialog) return;
      // getClientRects() over offsetParent: the dialog sits inside a fixed overlay, where
      // offsetParent is an unreliable visibility test
      const items = [...dialog.querySelectorAll(FOCUSABLE_SELECTOR)].filter(
        (el) => el.getClientRects().length > 0,
      );
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      // the !contains arm matters: focus can already be outside (a click on the backdrop, or a
      // browser that moved it), and without it Tab would keep walking the page behind
      if (e.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    historyCloseRef.current?.focus();
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [historyRegId]);

  // leaving the page cancels whatever is still in flight
  useEffect(
    () => () => {
      registrationsAbortRef.current?.abort();
      countsAbortRef.current?.abort();
    },
    [],
  );

  // ---- filter apply / clear (draft -> applied) ----
  const draftFilters = {
    subject: subjectFilter,
    searchQuery: searchInput,
    semester: semesterFilter,
    departmentId: deptFilter,
    examCycleId: cycleFilter,
  };
  const filtersDirty =
    JSON.stringify(draftFilters) !== JSON.stringify(appliedFilters);

  const applyFilters = () => {
    setPage(0); // a new filter set always starts from the first page
    setSelectedIds(new Set()); // ticked rows may not survive the new filters
    setAppliedFilters(draftFilters);
  };

  const clearFilters = () => {
    // reset to defaults: the active cycle (the page's default scope), no other filters
    const activeId = examCycles.find((c) => c.active)?.id;
    const cyc = activeId != null ? String(activeId) : "";
    setSubjectFilter("");
    setSearchInput("");
    setSemesterFilter("");
    setDeptFilter("");
    setCycleFilter(cyc);
    setPage(0);
    setSelectedIds(new Set());
    setAppliedFilters({
      subject: "",
      searchQuery: "",
      semester: "",
      departmentId: "",
      examCycleId: cyc,
    });
  };

  // Drop one regId-keyed entry, returning `prev` untouched when absent so React can bail out of
  // the re-render. Shared by the per-row error map and the history cache.
  const dropRegId = (setter, regId) =>
    setter((prev) => {
      if (!prev[regId]) return prev;
      const next = { ...prev };
      delete next[regId];
      return next;
    });

  const clearRowError = (regId) => dropRegId(setRowErrors, regId);

  // Verify/reject appends an event and moves the row, so the cached trail is stale and the table
  // and stat cards both need refetching. Every post-action path goes through here.
  const resyncAfterAction = async (regId) => {
    dropRegId(setHistoryCache, regId);
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
      await api.put(
        `/register/verify/${regId}`,
        { action: "VERIFIED" },
        { headers: getAdminHeaders() },
      );
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
      await api.put(
        `/register/verify/${regId}`,
        { action: "REJECTED" },
        { headers: getAdminHeaders() },
      );
      await resyncAfterAction(regId);
    } catch (err) {
      await handleActionError(regId, err, "Failed to reject. Please refresh and try again.");
    } finally {
      setRejectingRegId("");
      setConfirmRejectVerifiedId("");
    }
  };

  const openHistory = async (regId) => {
    historyTriggerRef.current = document.activeElement;
    setHistoryRegId(regId);
    setHistoryError("");
    historyReqRef.current = regId;
    if (historyCache[regId]) {
      setHistoryLoading(false);
      return;
    }

    setHistoryLoading(true);
    try {
      const res = await api.get(`/admin/registrations/${regId}/events`, {
        headers: getAdminHeaders(),
      });
      setHistoryCache((prev) => ({
        ...prev,
        [regId]: Array.isArray(res.data) ? res.data : [],
      }));
    } catch (err) {
      // Surface it: a failed fetch and a genuinely empty trail render identically otherwise, so a
      // 403/404/network drop would read as "nobody actioned this registration" — the one claim an
      // audit view must never make on its own failure. Nothing is cached, so reopening retries.
      console.error("Failed to load history", err);
      if (historyReqRef.current === regId) {
        setHistoryError(
          err.response?.data?.message || "Unable to load history. Please try again.",
        );
      }
    } finally {
      if (historyReqRef.current === regId) setHistoryLoading(false);
    }
  };

  // What the export covers. Ticked rows win outright; otherwise it mirrors exactly what the table
  // is showing — including the cycle and the status tab, which the old GET silently narrowed to
  // "the active cycle, verified only" no matter what was on screen.
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
        headers: getAdminHeaders(),
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

  // dropdown groups — the type distinction lives in the option list, not in a second control
  const regularSubjects = allSubjects.filter((s) => s.subjectType === "REGULAR");
  const electiveSubjects = allSubjects.filter((s) => s.subjectType === "ELECTIVE");

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

  // Stat cards come from the server counts endpoint and span every status of the filtered set
  // (the list's filters minus the status tab), whichever tab or page is open.
  // A failed counts fetch must not render as a number: zero and stale both read as fact.
  const statValue = (n) => (countsError ? "—" : n);

  if (!isAdmin || !adminToken) {
    return (
      <div className="min-h-screen bg-surface-1 px-4 py-10 sm:px-6 lg:px-8">
        <div className="mx-auto w-full max-w-2xl rounded-3xl border border-stroke bg-surface-1 p-8 text-center shadow-soft">
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
    <div className="min-h-screen bg-surface-1 px-4 py-8 text-ink sm:px-6 lg:px-8">
      <a
        href="#admin-main"
        className="sr-only left-4 top-4 z-[60] rounded-md bg-cta px-4 py-2 text-sm font-semibold text-cta-text focus:not-sr-only focus:fixed"
      >
        Skip to admin table
      </a>

      <div id="admin-main" className="mx-auto w-full max-w-7xl">
        <BrandHeader
          className="mb-6"
          badge={
            adminDepartment && (
              <div className="mt-2 flex flex-wrap gap-2">
                <p className="inline-flex rounded-full border border-white/25 bg-white/10 px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.12em] text-white">
                  {adminDepartment}
                </p>
              </div>
            )
          }
        >
          <div className="flex flex-wrap gap-2">
            {adminRole === "ADMIN" && (
              <Link
                to="/admin/exam-cycles"
                className="inline-flex items-center gap-1 rounded-full border border-white/35 bg-white/20 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/30"
              >
                <CalendarRange size={14} /> Exam Cycles
              </Link>
            )}
            {["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE"].includes(adminRole) && (
              <Link
                to="/admin/manage-subjects"
                className="inline-flex items-center gap-1 rounded-full border border-white/35 bg-white/20 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/30"
              >
                <BookOpen size={14} /> Subjects
              </Link>
            )}
            {(adminRole === "ADMIN" || adminRole === "PRINCIPAL") && (
              <Link
                to="/admin/departments"
                className="inline-flex items-center gap-1 rounded-full border border-white/35 bg-white/20 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/30"
              >
                <Building2 size={14} /> Departments
              </Link>
            )}
            {["ADMIN", "PRINCIPAL", "HOD"].includes(adminRole) && (
              <Link
                to="/admin/users"
                className="inline-flex items-center gap-1 rounded-full border border-white/35 bg-white/20 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/30"
              >
                <Users size={14} /> Users
              </Link>
            )}
            {["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR"].includes(adminRole) && (
              <Link
                to="/admin/students"
                className="inline-flex items-center gap-1 rounded-full border border-white/35 bg-white/20 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/30"
              >
                <IdCard size={14} /> {adminRole === "PROCTOR" ? "My Students" : "Students"}
              </Link>
            )}
            {/* ungated: accounts start on the derived default password, so every admin role needs
                a way here — Manage Users only reaches ADMIN/PRINCIPAL/HOD */}
            <Link
              to="/admin/change-password"
              className="inline-flex items-center gap-1 rounded-full border border-white/35 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/10"
            >
              <KeyRound size={14} /> My Password
            </Link>
            <ThemeToggle />
            <Link
              to="/"
              className="inline-flex items-center gap-1 rounded-full border border-white/35 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/10"
            >
              <ArrowLeft size={14} /> Home
            </Link>
            <button
              type="button"
              onClick={async () => {
                await logoutAdmin(); // expire the httpOnly cookie, then clear local state
                navigate("/admin/login");
              }}
              className="inline-flex items-center gap-1 rounded-full bg-cta px-4 py-2 text-sm font-semibold text-cta-text"
            >
              <LogOut size={14} /> Logout
            </button>
          </div>
        </BrandHeader>

        <section className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
          <div className="rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft">
            <p className="inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.1em] text-ink">
              <Users size={13} /> Total
            </p>
            <p className="mt-1 text-3xl font-semibold text-secondary-ink">
              {statValue(counts.total)}
            </p>
          </div>
          <div className="rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft">
            <p className="inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.1em] text-ink">
              <CircleDashed size={13} /> Pending
            </p>
            <p className="mt-1 text-3xl font-semibold text-secondary-ink">
              {statValue(counts.submitted)}
            </p>
          </div>
          <div className="rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft">
            <p className="inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.1em] text-ink">
              <Shield size={13} /> Verified
            </p>
            <p className="mt-1 text-3xl font-semibold text-secondary-ink">
              {statValue(counts.verified)}
            </p>
          </div>
          <div className="rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft">
            <p className="inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.1em] text-ink">
              <XCircle size={13} /> Rejected
            </p>
            <p className="mt-1 text-3xl font-semibold text-red-600">
              {statValue(counts.rejected)}
            </p>
          </div>
        </section>

        {countsError && (
          <AlertBanner
            tone="warning"
            role="status"
            data-cy="admin-counts-error"
            className="mb-6"
          >
            Totals unavailable — {countsError} The table below is unaffected.
          </AlertBanner>
        )}

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
            <div className="flex flex-col gap-1.5">
              <label
                htmlFor="cycle-filter"
                className="text-xs font-semibold uppercase tracking-[0.08em]"
              >
                Exam Cycle
              </label>
              <select
                id="cycle-filter"
                value={cycleFilter}
                onChange={(e) => setCycleFilter(e.target.value)}
                className="rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                <option value="">All Cycles</option>
                {examCycles.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                    {c.active ? " (active)" : ""}
                  </option>
                ))}
              </select>
            </div>

            {/* Department Filter — ADMIN/PRINCIPAL only; every other role is pinned server-side */}
            {canFilterByDepartment && (
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="dept-filter"
                  className="text-xs font-semibold uppercase tracking-[0.08em]"
                >
                  Department
                </label>
                <select
                  id="dept-filter"
                  value={deptFilter}
                  onChange={(e) => setDeptFilter(e.target.value)}
                  className="rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-focus-ring"
                >
                  <option value="">All Departments</option>
                  {departments.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.deptName}
                    </option>
                  ))}
                </select>
              </div>
            )}

            {/* Semester Filter */}
            {!isProctor && (
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="semester-filter"
                  className="text-xs font-semibold uppercase tracking-[0.08em]"
                >
                  Semester
                </label>
                <select
                  id="semester-filter"
                  value={semesterFilter}
                  onChange={(e) => setSemesterFilter(e.target.value)}
                  className="rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-focus-ring"
                >
                  <option value="">All Semesters</option>
                  {SEMESTERS.map((s) => (
                    <option key={s} value={s}>
                      Semester {s}
                    </option>
                  ))}
                </select>
              </div>
            )}

            {/* Subject Filter — carries the type distinction, so there is no separate type control */}
            {!isProctor && (
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="subject-filter"
                  className="text-xs font-semibold uppercase tracking-[0.08em]"
                >
                  Subject
                </label>
                <select
                  id="subject-filter"
                  value={subjectFilter}
                  onChange={(e) => setSubjectFilter(e.target.value)}
                  className="rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-focus-ring"
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
              </div>
            )}

            {/* Search Filter */}
            <div className="flex flex-col gap-1.5">
              <label
                htmlFor="search-filter"
                className="text-xs font-semibold uppercase tracking-[0.08em]"
              >
                Search by USN / Name
              </label>
              <input
                id="search-filter"
                type="text"
                placeholder="Enter USN or name..."
                data-cy="admin-search"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                className="rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring"
              />
            </div>
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

        <section className="rounded-3xl border border-stroke bg-surface-1 p-4 shadow-soft sm:p-6">
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
                  className={`rounded-full border px-4 py-2 text-xs font-semibold tracking-[0.06em] transition-transform duration-200 hover:scale-105 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:ring-offset-2 focus-visible:ring-offset-surface-1 ${
                    filter === f
                      ? "border-primary bg-primary text-white"
                      : "border-stroke bg-surface-1 text-secondary-ink"
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
                  className="inline-flex items-center gap-1.5 rounded-xl border border-stroke bg-surface-muted px-3 py-2 text-xs font-semibold text-secondary-ink transition-colors hover:border-primary"
                >
                  <X size={13} /> Clear {selectedIds.size} selected
                </button>
              )}
              <button
                type="button"
                onClick={handleExportPdf}
                disabled={isExporting}
                data-cy="admin-export-pdf"
                className="inline-flex items-center gap-1.5 rounded-xl bg-secondary px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-primary disabled:opacity-50"
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
            <p className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
              <LoaderCircle size={16} className="animate-spin" /> Loading
              registrations...
            </p>
          ) : (
            <div className="overflow-x-auto rounded-2xl border border-stroke">
              <table className="min-w-full border-collapse text-left text-sm">
                <thead>
                  <tr className="bg-surface-muted text-xs uppercase tracking-[0.08em] text-ink">
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
                        {reg.status === "SUBMITTED" && adminRole !== "PRINCIPAL" ? (
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
                                className="inline-flex w-15 items-center justify-center gap-1 rounded-lg border border-red-200 bg-red-50 py-1 text-xs font-semibold text-red-600 transition-colors hover:bg-red-100 disabled:opacity-50"
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
                            {confirmRejectVerifiedId === reg.regId ? (
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
                                  onClick={() => setConfirmRejectVerifiedId("")}
                                  disabled={rejectingRegId === reg.regId}
                                  data-cy="admin-reject-verified-cancel"
                                  className="inline-flex w-14 items-center justify-center rounded-lg border border-stroke py-1.5 text-xs font-semibold text-ink transition-colors hover:bg-surface-muted disabled:opacity-50"
                                >
                                  Cancel
                                </button>
                              </div>
                            ) : (
                              <div className="flex flex-col items-start gap-1">
                                <span className="text-sm font-semibold text-primary-ink">
                                  Verified
                                </span>
                                {adminRole !== "PRINCIPAL" ? (
                                  <button
                                    type="button"
                                    onClick={() => {
                                      clearRowError(reg.regId);
                                      setConfirmRejectVerifiedId(reg.regId);
                                    }}
                                    data-cy="admin-reject-verified"
                                    className="inline-flex w-14 items-center justify-center rounded-lg border border-red-200 py-1.5 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50"
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
                          onClick={() => openHistory(reg.regId)}
                          data-cy={`history-${reg.regId}`}
                          className="inline-flex items-center gap-1 rounded-lg border border-stroke bg-surface-1 px-3 py-1.5 text-xs font-semibold text-secondary-ink transition-colors hover:bg-surface-muted"
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
                  className="inline-flex items-center gap-1 rounded-lg border border-stroke bg-surface-1 px-3 py-1.5 text-xs font-semibold text-secondary-ink transition-colors hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <ArrowLeft size={14} /> Prev
                </button>
                <button
                  type="button"
                  onClick={() => setPage((p) => Math.min(pageInfo.totalPages - 1, p + 1))}
                  disabled={page >= pageInfo.totalPages - 1}
                  data-cy="admin-page-next"
                  className="inline-flex items-center gap-1 rounded-lg border border-stroke bg-surface-1 px-3 py-1.5 text-xs font-semibold text-secondary-ink transition-colors hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Next <ArrowLeft size={14} className="rotate-180" />
                </button>
              </div>
            </div>
          )}
        </section>
      </div>

      {historyRegId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onClick={closeHistory}
          role="presentation"
        >
          <div
            ref={historyDialogRef}
            className="w-full max-w-lg rounded-2xl border border-stroke bg-surface-1 p-6 shadow-soft"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="history-dialog-title"
            data-cy="history-dialog"
          >
            <div className="mb-4 flex items-center justify-between">
              <h3
                id="history-dialog-title"
                className="inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink"
              >
                <History size={18} /> Registration History
              </h3>
              <button
                type="button"
                ref={historyCloseRef}
                onClick={closeHistory}
                data-cy="history-close"
                className="rounded-lg p-1.5 text-ink-muted transition-colors hover:bg-surface-muted"
                aria-label="Close history"
              >
                <X size={18} />
              </button>
            </div>

            {historyLoading ? (
              <p className="inline-flex items-center gap-2 text-sm text-ink">
                <LoaderCircle size={16} className="animate-spin" /> Loading history...
              </p>
            ) : historyError ? (
              <p
                className="text-sm font-medium text-red-600"
                role="alert"
                data-cy="admin-history-error"
              >
                {historyError}
              </p>
            ) : historyEvents.length === 0 ? (
              <p className="text-sm text-ink-muted">
                No history recorded for this registration.
              </p>
            ) : (
              <ol className="space-y-3">
                {historyEvents.map((ev, i) => (
                  <li
                    key={i}
                    className="flex items-start gap-3 rounded-xl border border-stroke bg-surface-muted px-3 py-2.5"
                  >
                    <span
                      className={`mt-0.5 rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${outcomeBadgeClass(
                        ev.action,
                        "bg-surface-1",
                      )}`}
                    >
                      {ev.action}
                    </span>
                    <div className="text-sm">
                      <p className="text-ink">
                        {ev.actor || "unknown"}
                        <span className="text-ink-muted">
                          {" "}
                          ({ev.actorRole})
                        </span>
                      </p>
                      <p className="text-xs text-ink-muted">
                        {ev.timestamp
                          ? new Date(ev.timestamp).toLocaleString()
                          : ""}
                      </p>
                      {ev.note && (
                        <p className="mt-1 text-xs text-ink">
                          {ev.note}
                        </p>
                      )}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

export default AdminPage;
