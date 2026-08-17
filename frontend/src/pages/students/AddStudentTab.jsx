import { useState, useMemo } from "react";
import { BadgeCheck, LoaderCircle, UserPlus } from "lucide-react";
import MagneticCta from "../../components/ui/MagneticCta";
import api, { getAdminHeaders } from "../../lib/api";
import { findOwnDepartment } from "../../lib/session";
import AlertBanner from "../../components/AlertBanner";
import { CURRENT_SEMESTERS, clampEntrySemester, entrySemestersUpTo } from "../../lib/semesters";

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60";

const USN_RE = /^1MS\d{2}[A-Za-z]{2}\d{3}$/;

const blank = {
  rollNo: "",
  name: "",
  phone: "",
  dateOfBirth: "",
  currentSemester: "2",
  entrySemester: "1",
};

// keep only digits, max 10
const cleanPhone = (v) => v.replace(/\D/g, "").slice(0, 10);

// Create one student. Presentational tab: the shell supplies departments and the dept-lock
// context. DOB is a write-only credential. The server seeds the full entry..8 academic-year
// timeline on create, so the success banner confirms it — corrections happen on the Manage tab.
function AddStudentTab({ departments, adminDepartment, deptLocked }) {
  const [form, setForm] = useState(blank);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [createdRollNo, setCreatedRollNo] = useState("");

  // dept code a dept-scoped admin's USNs must carry, shown as a hint
  const myDeptCode = useMemo(() => {
    if (!deptLocked) return "";
    return findOwnDepartment(departments, adminDepartment)?.code || "";
  }, [deptLocked, departments, adminDepartment]);

  const set = (name, value) => setForm((prev) => ({ ...prev, [name]: value }));

  // entry semester is odd and can't be after the current semester
  const current = Number(form.currentSemester) || 0;
  const entryOptions = entrySemestersUpTo(form.currentSemester);

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    setCreatedRollNo("");

    const rollNo = form.rollNo.trim().toUpperCase();
    if (!USN_RE.test(rollNo)) {
      setError("USN must be in the format 1MS22CS001.");
      return;
    }
    if (!form.name.trim()) {
      setError("Name is required.");
      return;
    }
    if (!form.dateOfBirth) {
      setError("Date of birth is required.");
      return;
    }
    const entry = Number(form.entrySemester);
    if (entry > current) {
      setError("Entry semester cannot be after the current semester.");
      return;
    }

    setLoading(true);
    try {
      await api.post(
        "/admin/students",
        {
          rollNo,
          name: form.name.trim(),
          phone: form.phone.trim() || null,
          dateOfBirth: form.dateOfBirth,
          currentSemester: current,
          entrySemester: entry,
        },
        { headers: getAdminHeaders() },
      );
      setCreatedRollNo(rollNo);
      setForm(blank);
    } catch (err) {
      setError(err.response?.data?.message || "Could not create the student.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-8"
    >
      <h2 className="mb-2 text-xl font-semibold text-secondary-ink">Add a student</h2>
      <p className="mb-6 text-sm text-ink-muted">
        The USN sets the branch and admission year, and the email is assigned automatically
        (usn@msrit.edu). Date of birth is the student's login credential. All eight semesters' academic
        years are seeded automatically from the USN on create.
        {deptLocked && myDeptCode ? ` Your USNs must use the ${myDeptCode} branch code.` : ""}
      </p>

      {createdRollNo && (
        <AlertBanner
          tone="success"
          role="status"
          data-cy="student-created-complete"
          icon={<BadgeCheck size={20} className="mt-0.5 shrink-0" />}
          className="mb-6"
        >
          <p className="font-semibold">{createdRollNo} added — full semester timeline seeded.</p>
          <p className="mt-1">
            Every semester's academic year (entry through 8) was recorded automatically from the
            USN, assuming no detention. Correct a year, or the current semester, from the student's
            card on the Manage tab.
          </p>
        </AlertBanner>
      )}

      <form onSubmit={submit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="rollNo" className="text-xs font-semibold uppercase tracking-[0.08em]">
              USN *
            </label>
            <input
              id="rollNo"
              className={`${inputClass} font-mono`}
              placeholder="e.g. 1MS22CS001"
              value={form.rollNo}
              onChange={(e) => set("rollNo", e.target.value.toUpperCase())}
              data-cy="student-usn"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="name" className="text-xs font-semibold uppercase tracking-[0.08em]">
              Name *
            </label>
            <input
              id="name"
              className={inputClass}
              placeholder="Full name"
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              data-cy="student-name"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="phone" className="text-xs font-semibold uppercase tracking-[0.08em]">
              Phone
            </label>
            <input
              id="phone"
              type="tel"
              inputMode="numeric"
              maxLength={10}
              className={inputClass}
              placeholder="optional, 10 digits"
              value={form.phone}
              onChange={(e) => set("phone", cleanPhone(e.target.value))}
              data-cy="student-phone"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="dob" className="text-xs font-semibold uppercase tracking-[0.08em]">
              Date of birth * <span className="text-ink-muted">(login credential)</span>
            </label>
            <input
              id="dob"
              type="date"
              className={inputClass}
              value={form.dateOfBirth}
              onChange={(e) => set("dateOfBirth", e.target.value)}
              data-cy="student-dob"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="currentSemester" className="text-xs font-semibold uppercase tracking-[0.08em]">
                Current sem *
              </label>
              <select
                id="currentSemester"
                className={inputClass}
                value={form.currentSemester}
                onChange={(e) => {
                  const v = e.target.value;
                  setForm((prev) => ({
                    ...prev,
                    currentSemester: v,
                    // keep entry ≤ current AND odd — snapping to v would produce an even entry
                    entrySemester: String(clampEntrySemester(prev.entrySemester, v)),
                  }));
                }}
                data-cy="student-current-sem"
              >
                {CURRENT_SEMESTERS.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="entrySemester" className="text-xs font-semibold uppercase tracking-[0.08em]">
                Entry sem
              </label>
              <select
                id="entrySemester"
                className={inputClass}
                value={form.entrySemester}
                onChange={(e) => set("entrySemester", e.target.value)}
                data-cy="student-entry-sem"
              >
                {entryOptions.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>
        <p className="text-xs text-ink-muted">
          Entry semester is 1 for a normal intake, or the semester a lateral-entry/migrant student
          joined at (e.g. 3 for a 2nd-year transfer). It raises their backlog-eligibility floor.
          Students sit in even semesters and join at odd ones — an academic year is a semester pair.
        </p>

        {error && (
          <AlertBanner tone="error" role="alert" data-cy="student-add-error">
            {error}
          </AlertBanner>
        )}

        <div className="border-t border-stroke pt-4">
          <MagneticCta
            type="submit"
            disabled={loading}
            className="w-full gap-2 rounded-xl"
            data-cy="student-add-submit"
          >
            {loading ? (
              <>
                <LoaderCircle size={16} className="animate-spin" /> Adding...
              </>
            ) : (
              <>
                <UserPlus size={16} /> Add student
              </>
            )}
          </MagneticCta>
        </div>
      </form>
    </div>
  );
}

export default AddStudentTab;
