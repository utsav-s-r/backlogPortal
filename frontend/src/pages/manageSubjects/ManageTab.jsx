import { useState, useCallback } from "react";
import {
  BookOpen,
  Check,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  Pencil,
  Search,
  Trash2,
  X,
} from "lucide-react";
import api, { getAdminHeaders } from "../../lib/api";
import { formatAcademicYear, parseAcademicYear, courseCodeSuffix } from "../../lib/academicYear";
import CourseCodeField from "../../components/ui/CourseCodeField";

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

const PAGE_SIZE = 25;

// Browse / edit / delete the subject catalog. Presentational tab: the shell supplies departments
// and the dept-lock context, this only filters and loads.
function ManageTab({ departments, adminDepartment, deptLocked, pinnedDeptId }) {
  const [fDeptId, setFDeptId] = useState("");
  const [fYear, setFYear] = useState("");
  const [fSemester, setFSemester] = useState("");

  const [subjects, setSubjects] = useState(null); // null = not loaded yet
  // server-side pagination: mirrors the Spring Page envelope (0-based `number`)
  const [pageInfo, setPageInfo] = useState({ number: 0, totalPages: 0, totalElements: 0 });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const effectiveDeptId = deptLocked ? pinnedDeptId : fDeptId;

  // The endpoint returns a Page ({content, totalPages, ...}), never a bare array — loading the
  // whole catalog unfiltered used to time the client out. Each call pulls one page; filters reset
  // to page 0.
  const loadSubjects = useCallback(async (targetPage = 0) => {
    setError("");
    setBusy(true);
    try {
      const params = { page: targetPage, size: PAGE_SIZE };
      if (effectiveDeptId) params.deptId = Number(effectiveDeptId);
      const y = parseAcademicYear(fYear);
      if (fYear && !Number.isNaN(y)) params.academicYearOffered = y;
      if (fSemester) params.semester = Number(fSemester);
      const res = await api.get("/admin/subjects", { headers: getAdminHeaders(), params });
      const data = res.data || {};
      setSubjects(Array.isArray(data.content) ? data.content : []);
      setPageInfo({
        number: data.number ?? 0,
        totalPages: data.totalPages ?? 0,
        totalElements: data.totalElements ?? 0,
      });
    } catch (err) {
      // back to null ("not loaded"), not []: the previous department's rows would read as this
      // department's, and [] would claim "No subjects match these filters" — both assert a result
      // the failed request never returned.
      setSubjects(null);
      setError(
        err.response?.data?.message ||
          (err.code === "ECONNABORTED"
            ? "Loading timed out. Narrow the filters and try again."
            : "Could not load subjects."),
      );
    } finally {
      setBusy(false);
    }
  }, [effectiveDeptId, fYear, fSemester]);

  const onUpdated = (updated) =>
    setSubjects((prev) => prev.map((s) => (s.id === updated.id ? updated : s)));
  const onRemoved = (id) => setSubjects((prev) => prev.filter((s) => s.id !== id));

  return (
    <>
      <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
        <h1 className="mb-1 inline-flex items-center gap-2 text-xl font-semibold text-secondary-ink">
          <BookOpen size={18} /> Manage subjects
        </h1>
        <p className="mb-4 text-sm text-ink-muted">
          Browse the catalog and fix subjects. The academic year can't be changed (it's the
          year-binding key); the course-code prefix stays locked to it.
        </p>
        {deptLocked && adminDepartment && (
          <p className="mb-4 text-xs font-semibold text-primary-ink">
            Scoped to {adminDepartment}
          </p>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Department</label>
            <select
              className={inputClass}
              value={effectiveDeptId}
              onChange={(e) => setFDeptId(e.target.value)}
              disabled={deptLocked}
              data-cy="subjects-dept"
            >
              <option value="">All departments</option>
              {departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.deptName}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Academic year</label>
            <input
              className={inputClass}
              type="text"
              placeholder="e.g. 2024-25 (optional)"
              value={fYear}
              onChange={(e) => setFYear(e.target.value)}
              data-cy="subjects-year"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-semibold uppercase tracking-[0.08em]">Semester</label>
            <select
              className={inputClass}
              value={fSemester}
              onChange={(e) => setFSemester(e.target.value)}
              data-cy="subjects-sem"
            >
              <option value="">All</option>
              {[1, 2, 3, 4, 5, 6, 7, 8].map((s) => (
                <option key={s} value={s}>
                  Semester {s}
                </option>
              ))}
            </select>
          </div>
        </div>

        {error && (
          <p className="mt-3 text-sm text-red-600" role="alert" data-cy="subjects-error">
            {error}
          </p>
        )}

        <div className="mt-4">
          <button
            type="button"
            onClick={() => loadSubjects(0)}
            disabled={busy}
            data-cy="subjects-load"
            className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-2 text-sm font-semibold transition-colors hover:border-primary disabled:opacity-60"
          >
            {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />} Load subjects
          </button>
        </div>
      </section>

      {subjects && (
        <section className="mt-6 flex flex-col gap-3">
          {subjects.length === 0 ? (
            <p
              className="rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm"
              data-cy="subjects-empty"
            >
              No subjects match these filters.
            </p>
          ) : (
            <>
              {subjects.map((subject) => (
                <SubjectRow
                  key={subject.id}
                  subject={subject}
                  departments={departments}
                  onUpdated={onUpdated}
                  onRemoved={onRemoved}
                />
              ))}
              {pageInfo.totalPages > 1 && (
                <Pager pageInfo={pageInfo} busy={busy} onGo={loadSubjects} noun="subjects" />
              )}
            </>
          )}
        </section>
      )}
    </>
  );
}

// Prev/next pager over a Spring Page envelope. `onGo(pageIndex)` re-fetches, keeping the current
// filters (the loader reads them from state).
function Pager({ pageInfo, busy, onGo, noun }) {
  const { number, totalPages, totalElements } = pageInfo;
  return (
    <div
      className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm"
      data-cy={`${noun}-pager`}
    >
      <span className="text-ink-muted">
        Page {number + 1} of {totalPages} · {totalElements} {noun}
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => onGo(number - 1)}
          disabled={busy || number <= 0}
          data-cy={`${noun}-prev`}
          className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-40"
        >
          <ChevronLeft size={13} /> Prev
        </button>
        <button
          type="button"
          onClick={() => onGo(number + 1)}
          disabled={busy || number >= totalPages - 1}
          data-cy={`${noun}-next`}
          className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-40"
        >
          Next <ChevronRight size={13} />
        </button>
      </div>
    </div>
  );
}

// Module scope, so identity is stable across parent renders and inputs keep focus.
function SubjectRow({ subject, departments, onUpdated, onRemoved }) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(subject.subjectName || "");
  const [code, setCode] = useState(subject.courseCode || "");
  const [semester, setSemester] = useState(String(subject.semester));
  const [credits, setCredits] = useState(String(subject.credits));
  const [type, setType] = useState(subject.subjectType || "REGULAR");
  const [eligible, setEligible] = useState((subject.eligibleDepartments || []).map((d) => d.id));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const startEdit = () => {
    setName(subject.subjectName || "");
    setCode(subject.courseCode || "");
    setSemester(String(subject.semester));
    setCredits(String(subject.credits));
    setType(subject.subjectType || "REGULAR");
    setEligible((subject.eligibleDepartments || []).map((d) => d.id));
    setError("");
    setEditing(true);
  };

  const toggleEligible = (id) =>
    setEligible((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const save = async () => {
    if (!courseCodeSuffix(code)) {
      setError("Enter the course code.");
      return;
    }
    if (type === "ELECTIVE" && eligible.length === 0) {
      setError("Select at least one eligible department.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const res = await api.put(
        `/admin/subjects/${subject.id}`,
        {
          subjectName: name,
          courseCode: code,
          semester: Number(semester),
          credits: Number(credits),
          subjectType: type,
          eligibleDeptIds: type === "ELECTIVE" ? eligible : [],
        },
        { headers: getAdminHeaders() },
      );
      onUpdated(res.data);
      setEditing(false);
    } catch (err) {
      setError(err.response?.data?.message || "Could not save.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!window.confirm(`Delete "${subject.subjectName}" (${subject.courseCode})?`)) return;
    setBusy(true);
    setError("");
    try {
      await api.delete(`/admin/subjects/${subject.id}`, { headers: getAdminHeaders() });
      onRemoved(subject.id);
    } catch (err) {
      setError(err.response?.data?.message || "Could not delete.");
      setBusy(false);
    }
  };

  const card =
    "rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft";

  if (!editing) {
    return (
      <div className={card}>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="font-semibold text-ink">{subject.subjectName}</p>
            <p className="text-xs text-ink-muted">
              {subject.courseCode} · Sem {subject.semester} · {subject.credits} credits ·{" "}
              {subject.subjectType} · {formatAcademicYear(subject.academicYearOffered)}
            </p>
            {subject.subjectType === "ELECTIVE" && (subject.eligibleDepartments || []).length > 0 && (
              <p className="mt-1 text-xs text-ink-muted">
                Eligible: {(subject.eligibleDepartments || []).map((d) => d.deptName).join(", ")}
              </p>
            )}
          </div>
          <div className="flex shrink-0 gap-2">
            <button
              type="button"
              onClick={startEdit}
              data-cy={`subject-edit-${subject.id}`}
              className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary"
            >
              <Pencil size={13} /> Edit
            </button>
            <button
              type="button"
              onClick={remove}
              disabled={busy}
              data-cy={`subject-delete-${subject.id}`}
              className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50 disabled:opacity-60"
            >
              {busy ? <LoaderCircle size={13} className="animate-spin" /> : <Trash2 size={13} />} Delete
            </button>
          </div>
        </div>
        {error && (
          <p className="mt-2 text-xs text-red-600" role="alert" data-cy={`subject-error-${subject.id}`}>
            {error}
          </p>
        )}
      </div>
    );
  }

  return (
    <div className={card}>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Subject name</label>
          <input
            className={inputClass}
            value={name}
            onChange={(e) => setName(e.target.value)}
            data-cy="subject-name"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Course code</label>
          <CourseCodeField
            year={subject.academicYearOffered}
            value={code}
            onChange={setCode}
            inputClassName={inputClass}
            dataCy="subject-code"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Semester</label>
          <select className={inputClass} value={semester} onChange={(e) => setSemester(e.target.value)}>
            {[1, 2, 3, 4, 5, 6, 7, 8].map((s) => (
              <option key={s} value={s}>
                Semester {s}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Credits</label>
          <input
            className={inputClass}
            type="number"
            min="0"
            value={credits}
            onChange={(e) => setCredits(e.target.value)}
            data-cy="subject-credits"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">
            Academic year (locked)
          </label>
          <div className="flex h-[42px] items-center rounded-xl border border-stroke bg-surface-muted px-3.5 text-sm text-ink-muted">
            {formatAcademicYear(subject.academicYearOffered)}
          </div>
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-semibold uppercase tracking-[0.08em]">Type</label>
          <select className={inputClass} value={type} onChange={(e) => setType(e.target.value)}>
            <option value="REGULAR">Regular</option>
            <option value="ELECTIVE">Elective</option>
          </select>
        </div>
      </div>

      {type === "ELECTIVE" && (
        <div className="mt-3 flex flex-col gap-2">
          <span className="text-xs font-semibold uppercase tracking-[0.08em]">Eligible departments</span>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {departments.map((d) => (
              <label key={d.id} className="flex cursor-pointer items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={eligible.includes(d.id)}
                  onChange={() => toggleEligible(d.id)}
                  className="h-4 w-4 accent-primary"
                />
                {d.deptName}
              </label>
            ))}
          </div>
        </div>
      )}

      {error && (
        <p className="mt-3 text-sm text-red-600" role="alert" data-cy="subject-edit-error">
          {error}
        </p>
      )}

      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={save}
          disabled={busy}
          data-cy="subject-save"
          className="inline-flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-sm font-semibold text-white disabled:opacity-60"
        >
          {busy ? <LoaderCircle size={14} className="animate-spin" /> : <Check size={14} />} Save
        </button>
        <button
          type="button"
          onClick={() => setEditing(false)}
          className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-2 text-sm font-semibold transition-colors hover:border-primary"
        >
          <X size={14} /> Cancel
        </button>
      </div>
    </div>
  );
}

export default ManageTab;
