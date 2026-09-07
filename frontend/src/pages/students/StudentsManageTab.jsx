import { useState, useCallback, useEffect } from "react";
import {
  AlertTriangle,
  CalendarClock,
  Check,
  KeyRound,
  LoaderCircle,
  Pencil,
  Search,
  Trash2,
  UserMinus,
  X,
} from "lucide-react";
import AlertBanner from "../../components/AlertBanner";
import api from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";
import { parseAcademicYear } from "../../lib/academicYear";
import {
  ALL_SEMESTERS,
  CURRENT_SEMESTERS,
  clampEntrySemester,
  entrySemestersUpTo,
  withLegacyValue,
} from "../../lib/semesters";
import { SemesterTimeline } from "./SemesterTimeline";
import { ROLE } from "../../lib/roles";
import { FIELD_INPUT } from "../../lib/formClasses";
import Field from "../../components/ui/Field";
import Pager from "../../components/ui/Pager";
import DepartmentOptions from "../../components/ui/DepartmentOptions";
import { btn } from "../../lib/buttonClasses";

const PAGE_SIZE = 25;

// Browse / edit / delete / reset-DOB the student roster. Presentational tab; the shell supplies
// departments and the dept-lock context. For a PROCTOR the server already limits the list to
// assigned students, and "delete" means unassign from supervision, never an account delete.
function StudentsManageTab({ departments, adminRole, adminDepartment, deptLocked, pinnedDeptId }) {
  const proctorMode = adminRole === ROLE.PROCTOR;
  const [fDeptId, setFDeptId] = useState("");
  const [fYear, setFYear] = useState("");
  const [fSemester, setFSemester] = useState("");
  const [fQuery, setFQuery] = useState("");

  const [students, setStudents] = useState(null); // null = not loaded yet
  // server-side pagination: mirrors the Spring Page envelope (0-based `number`)
  const [pageInfo, setPageInfo] = useState({ number: 0, totalPages: 0, totalElements: 0 });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const effectiveDeptId = deptLocked ? pinnedDeptId : fDeptId;

  // The endpoint returns a Page ({content, totalPages, ...}), never a bare array — loading the
  // whole roster unfiltered times the client out. Each call pulls one page; filters reset to page 0.
  const load = useCallback(async (targetPage = 0) => {
    setError("");
    setBusy(true);
    try {
      const params = { page: targetPage, size: PAGE_SIZE };
      if (effectiveDeptId) params.deptId = Number(effectiveDeptId);
      if (fYear && /^\d{4}$/.test(fYear.trim())) params.admissionYear = Number(fYear.trim());
      if (fSemester) params.semester = Number(fSemester);
      if (fQuery.trim()) params.query = fQuery.trim();
      const res = await api.get("/admin/students", { params });
      const data = res.data || {};
      setStudents(Array.isArray(data.content) ? data.content : []);
      setPageInfo({
        number: data.number ?? 0,
        totalPages: data.totalPages ?? 0,
        totalElements: data.totalElements ?? 0,
      });
    } catch (err) {
      setError(
        err.response?.data?.message ||
          (err.code === "ECONNABORTED"
            ? "Loading timed out. Narrow the filters and try again."
            : "Could not load students."),
      );
    } finally {
      setBusy(false);
    }
  }, [effectiveDeptId, fYear, fSemester, fQuery]);

  const onUpdated = (updated) =>
    setStudents((prev) => prev.map((s) => (s.rollNo === updated.rollNo ? updated : s)));
  const onRemoved = (rollNo) =>
    setStudents((prev) => prev.filter((s) => s.rollNo !== rollNo));

  return (
    <>
      <section className="py-5 sm:py-6">
        {deptLocked && adminDepartment && (
          <p className="mb-4 text-xs font-semibold text-primary-ink">
            Scoped to {adminDepartment}
          </p>
        )}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
          <Field label="Department">
            <select
              className={FIELD_INPUT}
              value={effectiveDeptId}
              onChange={(e) => setFDeptId(e.target.value)}
              disabled={deptLocked}
              data-cy="students-dept"
            >
              <option value="">All departments</option>
              <DepartmentOptions departments={departments} />
            </select>
          </Field>
          <Field label="Admission year">
            <input
              className={FIELD_INPUT}
              type="text"
              placeholder="e.g. 2024"
              value={fYear}
              onChange={(e) => setFYear(e.target.value)}
              data-cy="students-year"
            />
          </Field>
          <Field label="Semester">
            <select
              className={FIELD_INPUT}
              value={fSemester}
              onChange={(e) => setFSemester(e.target.value)}
              data-cy="students-sem"
            >
              <option value="">All</option>
              {/* ALL_SEMESTERS, not CURRENT_SEMESTERS: a filter READS existing data. Narrowing it
                  to even would make legacy odd-semester students — the ones bulk progression
                  reports as "fix by hand" — unfindable. */}
              {ALL_SEMESTERS.map((s) => (
                <option key={s} value={s}>
                  Semester {s}
                </option>
              ))}
            </select>
          </Field>
          <Field label="USN / name">
            <input
              className={FIELD_INPUT}
              type="text"
              placeholder="search"
              value={fQuery}
              onChange={(e) => setFQuery(e.target.value)}
              data-cy="students-query"
            />
          </Field>
        </div>

        {error && (
          <p className="mt-3 text-sm text-red-600" role="alert" data-cy="students-error">
            {error}
          </p>
        )}

        <div className="mt-4">
          <button
            type="button"
            onClick={() => load(0)}
            disabled={busy}
            data-cy="students-load"
            className={btn()}
          >
            {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />} Load students
          </button>
        </div>
      </section>

      {students && (
        <section className="mt-6 flex flex-col gap-3">
          {students.length === 0 ? (
            <p
              className="rounded-lg bg-surface-muted px-4 py-3 text-sm"
              data-cy="students-empty"
            >
              No students match these filters.
            </p>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="min-w-full border-collapse text-left text-sm">
                  <thead>
                    <tr className="border-b border-stroke text-xs uppercase tracking-[0.08em] text-ink-muted">
                      <th className="px-4 py-3 font-semibold">USN</th>
                      <th className="px-4 py-3 font-semibold">Name</th>
                      <th className="px-4 py-3 font-semibold">Dept</th>
                      <th className="px-4 py-3 font-semibold">Sem</th>
                      <th className="px-4 py-3 font-semibold">Entry</th>
                      <th className="px-4 py-3 font-semibold">Email</th>
                      <th className="px-4 py-3 font-semibold">Phone</th>
                      <th className="px-4 py-3 font-semibold">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {students.map((student) => (
                      <StudentRow
                        key={student.rollNo}
                        student={student}
                        proctorMode={proctorMode}
                        onUpdated={onUpdated}
                        onRemoved={onRemoved}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager pageInfo={pageInfo} busy={busy} onGo={load} noun="students" />
            </>
          )}
        </section>
      )}
    </>
  );
}

// Module scope, so identity is stable across parent renders and inputs keep focus.
function StudentRow({ student, proctorMode, onUpdated, onRemoved }) {
  const [mode, setMode] = useState("view"); // view | edit | dob
  const [showSems, setShowSems] = useState(false); // toggle the sem-year timeline
  const [name, setName] = useState(student.name || "");
  const [email, setEmail] = useState(student.email || "");
  const [phone, setPhone] = useState(student.phone || "");
  const [currentSemester, setCurrentSemester] = useState(String(student.currentSemester));
  const [entrySemester, setEntrySemester] = useState(String(student.entrySemester));
  const [dob, setDob] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const startEdit = () => {
    setName(student.name || "");
    setEmail(student.email || "");
    setPhone(student.phone || "");
    setCurrentSemester(String(student.currentSemester));
    setEntrySemester(String(student.entrySemester));
    setError("");
    setNotice("");
    setMode("edit");
  };

  const semesterChanged = Number(currentSemester) !== student.currentSemester;

  const save = async () => {
    if (!name.trim()) {
      setError("Name is required.");
      return;
    }
    if (Number(entrySemester) > Number(currentSemester)) {
      setError("Entry semester cannot be after the current semester.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const res = await api.put(
        `/admin/students/${student.rollNo}`,
        {
          name: name.trim(),
          phone: phone.trim() || null,
          currentSemester: Number(currentSemester),
          entrySemester: Number(entrySemester),
        },
      );
      onUpdated(res.data);
      setMode("view");
    } catch (err) {
      setError(err.response?.data?.message || "Could not save.");
    } finally {
      setBusy(false);
    }
  };

  const saveDob = async () => {
    if (!dob) {
      setError("Pick a date.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api.post(`/admin/students/${student.rollNo}/reset-dob`, { dateOfBirth: dob });
      setDob("");
      setMode("view");
      setNotice("Date of birth updated.");
    } catch (err) {
      setError(err.response?.data?.message || "Could not reset date of birth.");
    } finally {
      setBusy(false);
    }
  };

  // For a proctor this only ends supervision, leaving the account; for other roles it deletes
  // the account (blocked server-side if referenced).
  const remove = async () => {
    const prompt = proctorMode
      ? `Remove ${student.name} (${student.rollNo}) from your supervision? Their account is not deleted.`
      : `Delete ${student.name} (${student.rollNo})?`;
    if (!window.confirm(prompt)) return;
    setBusy(true);
    setError("");
    try {
      if (proctorMode) {
        await api.delete(`/admin/proctor/assignments/${student.rollNo}`);
      } else {
        await api.delete(`/admin/students/${student.rollNo}`);
      }
      onRemoved(student.rollNo);
    } catch (err) {
      setError(err.response?.data?.message || (proctorMode ? "Could not remove." : "Could not delete."));
      setBusy(false);
    }
  };

  // A REPEATED list item, so spacing alone does not separate it — consecutive rows just run
  // together. The hairline is the no-box rule's third mechanism and the one that fits a list;
  // `first:border-t-0` keeps a rule off the top of the list, where there is nothing to divide.
  // students sit in even semesters and join at odd ones — two different lists, not one.
  // Legacy rows predate the parity rule, so a stored invalid value joins its list rather than
  // rendering as a blank select that submits something the admin never saw.
  const currentOptions = withLegacyValue(CURRENT_SEMESTERS, student.currentSemester);
  const entryOptions = withLegacyValue(
    entrySemestersUpTo(currentSemester),
    student.entrySemester,
  );
  const semesterNeedsFixing =
    !CURRENT_SEMESTERS.includes(Number(student.currentSemester)) ||
    !entrySemestersUpTo(8).includes(Number(student.entrySemester));

  if (mode === "view") {
    return (
      <>
        <tr className="border-t border-stroke align-top transition-colors hover:bg-surface-muted">
          <td className="px-4 py-3 font-mono text-xs font-semibold text-ink">{student.rollNo}</td>
          <td className="px-4 py-3 font-semibold text-ink">
            {student.name}
            {notice && (
              <p className="mt-1 text-xs font-semibold text-primary-ink">{notice}</p>
            )}
            {error && (
              <p
                className="mt-1 text-xs text-red-600"
                role="alert"
                data-cy={`student-error-${student.rollNo}`}
              >
                {error}
              </p>
            )}
          </td>
          <td className="px-4 py-3">{student.branch || "\u2014"}</td>
          <td className="px-4 py-3">{student.currentSemester}</td>
          <td className="px-4 py-3">{student.entrySemester}</td>
          <td className="px-4 py-3 text-ink-muted">{student.email || "\u2014"}</td>
          <td className="px-4 py-3 text-ink-muted">{student.phone || "\u2014"}</td>
          <td className="px-4 py-3">
            <div className="flex gap-1.5 whitespace-nowrap">
              <button
                type="button"
                onClick={() => setShowSems((v) => !v)}
                data-cy={`student-sems-${student.rollNo}`}
                aria-expanded={showSems}
                className={btn(showSems ? "primary" : "neutral", "sm")}
              >
                <CalendarClock size={13} /> Semesters
              </button>
              <button
                type="button"
                onClick={startEdit}
                data-cy={`student-edit-${student.rollNo}`}
                className={btn("neutral", "sm")}
              >
                <Pencil size={13} /> Edit
              </button>
              <button
                type="button"
                onClick={() => {
                  setDob("");
                  setError("");
                  setNotice("");
                  setMode("dob");
                }}
                data-cy={`student-dob-${student.rollNo}`}
                className={btn("neutral", "sm")}
              >
                <KeyRound size={13} /> Reset DOB
              </button>
              <button
                type="button"
                onClick={remove}
                disabled={busy}
                data-cy={`student-delete-${student.rollNo}`}
                className={btn("danger", "sm")}
              >
                {busy ? (
                  <LoaderCircle size={13} className="animate-spin" />
                ) : proctorMode ? (
                  <UserMinus size={13} />
                ) : (
                  <Trash2 size={13} />
                )}{" "}
                {proctorMode ? "Remove" : "Delete"}
              </button>
            </div>
          </td>
        </tr>
        {/* The timeline is its own full-width row: it is a panel about the student above it, not a
            value belonging to any one column. */}
        {showSems && (
          <tr className="border-t border-stroke">
            <td colSpan={8} className="px-4 pb-4">
              <StudentSemesters rollNo={student.rollNo} />
            </td>
          </tr>
        )}
      </>
    );
  }

  if (mode === "dob") {
    return (
      <tr className="border-t border-stroke">
        <td colSpan={8} className="px-4 py-4">
        <p className="mb-2 text-sm font-semibold">
          Reset date of birth — {student.name} ({student.rollNo})
        </p>
        <p className="mb-3 text-xs text-ink-muted">
          This is the student's login credential. The current value is never shown.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="date"
            className={`${FIELD_INPUT} max-w-xs`}
            value={dob}
            onChange={(e) => setDob(e.target.value)}
            data-cy={`student-dob-input-${student.rollNo}`}
          />
          <button
            type="button"
            onClick={saveDob}
            disabled={busy}
            data-cy={`student-dob-save-${student.rollNo}`}
            className={btn("primary")}
          >
            {busy ? <LoaderCircle size={14} className="animate-spin" /> : <Check size={14} />} Save
          </button>
          <button
            type="button"
            onClick={() => setMode("view")}
            className={btn()}
          >
            <X size={14} /> Cancel
          </button>
        </div>
        {error && (
          <p className="mt-2 text-xs text-red-600" role="alert">
            {error}
          </p>
        )}
        </td>
      </tr>
    );
  }

  // edit mode
  return (
    <tr className="border-t border-stroke">
      <td colSpan={8} className="px-4 py-4">
      <p className="mb-3 text-sm font-semibold">
        Edit {student.rollNo}{" "}
        <span className="text-xs font-normal text-ink-muted">
          (USN and DOB aren't editable here)
        </span>
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="Name">
          <input className={FIELD_INPUT} value={name} onChange={(e) => setName(e.target.value)} data-cy="student-edit-name" />
        </Field>
        <Field
          label={<>Email <span className="font-normal normal-case text-ink-muted">(auto, from USN)</span></>}
        >
          <input className={FIELD_INPUT} value={email} readOnly tabIndex={-1} />
        </Field>
        <Field label="Phone">
          <input
            className={FIELD_INPUT}
            type="tel"
            inputMode="numeric"
            maxLength={10}
            value={phone}
            onChange={(e) => setPhone(e.target.value.replace(/\D/g, "").slice(0, 10))}
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Current sem">
            <select
              className={FIELD_INPUT}
              value={currentSemester}
              onChange={(e) => {
                const v = e.target.value;
                setCurrentSemester(v);
                // keep entry odd AND ≤ current when current shrinks
                setEntrySemester(String(clampEntrySemester(entrySemester, v)));
              }}
              data-cy="student-edit-current-sem"
            >
              {currentOptions.map((s) => (
                <option key={s} value={s}>
                  {CURRENT_SEMESTERS.includes(s) ? s : `${s} — invalid, pick another`}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Entry sem">
            <select
              className={FIELD_INPUT}
              value={entrySemester}
              onChange={(e) => setEntrySemester(e.target.value)}
              data-cy="student-edit-entry-sem"
            >
              {entryOptions.map((s) => (
                <option key={s} value={s}>
                  {entrySemestersUpTo(8).includes(s) ? s : `${s} — invalid, pick another`}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </div>

      {semesterNeedsFixing && (
        <AlertBanner
          tone="warning"
          compact
          icon={<AlertTriangle size={14} className="mt-0.5 shrink-0" />}
          className="mt-3"
          data-cy="student-invalid-semester"
        >
          This record predates the current/entry semester rule (students sit in an even semester and
          join at an odd one). Pick valid values above — saving without changing them is rejected.
          Bulk progression skips this student until it's fixed.
        </AlertBanner>
      )}

      {semesterChanged && (
        <AlertBanner
          tone="warning"
          compact
          icon={<AlertTriangle size={14} className="mt-0.5 shrink-0" />}
          className="mt-3"
        >
          Changing the current semester changes which backlogs this student is eligible to
          register.
        </AlertBanner>
      )}

      {error && (
        <p className="mt-3 text-sm text-red-600" role="alert" data-cy="student-edit-error">
          {error}
        </p>
      )}

      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={save}
          disabled={busy}
          data-cy="student-save"
          className={btn("primary")}
        >
          {busy ? <LoaderCircle size={14} className="animate-spin" /> : <Check size={14} />} Save
        </button>
        <button
          type="button"
          onClick={() => setMode("view")}
          className={btn()}
        >
          <X size={14} /> Cancel
        </button>
        </div>
      </td>
    </tr>
  );
}

// Per-student sem-year timeline, loaded on demand under a student's card. THE progression surface:
// creation seeds entry..8 linearly (no detention assumed), and this is where a wrong year gets
// corrected — e.g. after a year-back, where the seeded year is wrong by construction. Covers only
// the per-semester years; current/entry semester stay in the row's Edit form.
function StudentSemesters({ rollNo }) {
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // NOTE: react-hooks/set-state-in-effect flags the setBusy/setError below. Intended and correct
  // — a leading busy/error reset for an API fetch, exactly the "synchronize with an external
  // system" case the rule carves out. A knowing lint error, deliberately not disabled.
  useEffect(() => {
    let ignore = false;
    setBusy(true);
    setError("");
    api
      .get(`/admin/progression/${rollNo}`)
      .then((res) => {
        if (ignore) return;
        setData(res.data);
        setBusy(false);
      })
      // no .finally: on a 401 the spinner must stay up until the redirect lands
      .catch((err) => {
        if (!ignore && reportLoadError(err, setError, "Could not load semesters.")) setBusy(false);
      });
    return () => {
      ignore = true;
    };
  }, [rollNo]);

  const saveYear = async (semester, yearStr) => {
    const parsed = parseAcademicYear(yearStr);
    if (Number.isNaN(parsed)) {
      setError("Enter the academic year as a range or start year, e.g. 2024-25.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const res = await api.put(
        `/admin/progression/${rollNo}/semester/${semester}`,
        { academicYear: parsed },
      );
      setData(res.data);
    } catch (err) {
      setError(err.response?.data?.message || "Could not save.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mt-3 border-t border-stroke pt-3" data-cy={`student-sems-panel-${rollNo}`}>
      <p className="mb-2 text-xs text-ink-muted">
        Academic year the student studied each semester (entry through 8) — this is what their
        backlog subjects resolve against. Seeded on create assuming no detention, so correct any
        year a year-back changed. Blank rows aren't set yet; semesters past the current one are
        muted but still editable.
      </p>
      {busy && !data ? (
        <p className="inline-flex items-center gap-2 text-sm text-ink-muted">
          <LoaderCircle size={15} className="animate-spin" /> Loading semesters…
        </p>
      ) : data ? (
        <SemesterTimeline student={data} onSaveYear={saveYear} busy={busy} />
      ) : null}
      {error && (
        <p className="mt-2 text-xs text-red-600" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export default StudentsManageTab;
