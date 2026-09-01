import { useEffect, useState } from "react";
import api from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";

/**
 * Every filter on the admin dashboard: the DRAFT the admin is editing, the APPLIED set the table
 * and counts key off, and the three fetches that populate the dropdowns.
 *
 * That draft/applied split is the reason this is worth its own module. Fourteen useStates look like
 * sprawl but are two layers: eleven private draft values, and one `appliedFilters` object that is
 * the hook's real output. Nothing fetches off the draft, so editing fires zero requests until Apply.
 *
 * Own module, not a helper inside FilterPanel.jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 *
 * `adminToken` is deliberately NOT a parameter: every /admin/* route is wrapped in
 * ProtectedAdminRoute, which redirects when the marker is absent, so a page body cannot run
 * without one. See hooks/useRoleGuard.js — this is the same unreachable check deleted from six
 * pages, and a new shared API is the last place it should be reintroduced.
 *
 * @param onApplied  page-owned resets to run whenever the applied set changes (back to page 0,
 *                   drop the export selection). Injected rather than done here — neither piece of
 *                   state belongs to the filters.
 */
export function useRegistrationFilters({ isAdmin, canFilterByDepartment, onApplied }) {
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
  // closed ones and the PDF export follows via allCycles. THREE states, not a boolean: pending must be distinguishable from
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

  // Subject-dropdown options follow the APPLIED filters like the table, not the draft: editing fires
  // zero requests until Apply instead of a DISTINCT-join query per keystroke. Deps are the endpoint's
  // four fields, not the whole appliedFilters object, so an Apply changing only the exam cycle won't
  // re-fetch identical options. A narrowed list never auto-clears the current selection — applying a
  // now-unlisted subject just yields no rows.
  useEffect(() => {
    if (!isAdmin) {
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
    appliedFilters.searchQuery,
    appliedFilters.semester,
    appliedFilters.departmentId,
  ]);

  // Department options for the ADMIN/PRINCIPAL department filter. Everyone else is dept-pinned,
  // so there is nothing to choose and nothing to fetch.
  useEffect(() => {
    if (!isAdmin || !canFilterByDepartment) return;
    let ignore = false;
    const controller = new AbortController();
    api
      .get("/admin/departments", { signal: controller.signal })
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
  }, [isAdmin, canFilterByDepartment]);

  useEffect(() => {
    if (!isAdmin) return;
    let ignore = false;
    const controller = new AbortController();
    api
      .get("/admin/exam-cycles", { signal: controller.signal })
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
  }, [isAdmin]);

  // ---- filter apply / clear (draft -> applied) ----
  // Field-by-field, NOT JSON.stringify of two objects: that comparison is key-ORDER dependent, so
  // a future setAppliedFilters written with the keys in another order would leave the "Unapplied
  // changes" badge stuck on forever — and no test could see it. This also keeps the draft object
  // out of render; it is built only in applyFilters, which is an event handler.
  const filtersDirty =
    subjectFilter !== appliedFilters.subject ||
    searchInput !== appliedFilters.searchQuery ||
    semesterFilter !== appliedFilters.semester ||
    deptFilter !== appliedFilters.departmentId ||
    cycleFilter !== appliedFilters.examCycleId;

  const applyFilters = () => {
    onApplied(); // page 0 + drop the export selection — both owned by the page
    setAppliedFilters({
      subject: subjectFilter,
      searchQuery: searchInput,
      semester: semesterFilter,
      departmentId: deptFilter,
      examCycleId: cycleFilter,
    });
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
    onApplied();
    setAppliedFilters({
      subject: "",
      searchQuery: "",
      semester: "",
      departmentId: "",
      examCycleId: cyc,
    });
  };

  return {
    // the output the page's fetches key off
    appliedFilters,
    // "loading" | "loaded" | "error" — the export's fail-closed guard and the scope warning both
    // read this, so it must stay distinguishable from "the user picked All Cycles"
    cyclesStatus,
    // everything FilterPanel renders from — its whole prop set bar the two role flags, which
    // are the page's to decide
    panel: {
      filtersDirty,
      applyFilters,
      clearFilters,
      allSubjects,
      loadingSubjects,
      subjectFilter,
      setSubjectFilter,
      semesterFilter,
      setSemesterFilter,
      deptFilter,
      setDeptFilter,
      departments,
      searchInput,
      setSearchInput,
      examCycles,
      cycleFilter,
      setCycleFilter,
      cyclesError,
      subjectsError,
      departmentsError,
    },
  };
}
