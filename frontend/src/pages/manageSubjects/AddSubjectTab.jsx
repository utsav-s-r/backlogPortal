import { useState, useEffect } from "react";
import { LoaderCircle, PlusCircle } from "lucide-react";
import MagneticCta from "../../components/ui/MagneticCta";
import api, { getAdminHeaders } from "../../lib/api";
import { formatAcademicYear, buildCourseCode, courseCodeSuffix } from "../../lib/academicYear";
import CourseCodeField from "../../components/ui/CourseCodeField";
import AlertBanner from "../../components/AlertBanner";

const inputClass =
  "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring";

// Add a single subject. Presentational tab: the shell supplies departments and the dept-lock
// context, this keeps only form state.
function AddSubjectTab({ departments, adminDepartment, deptLocked, pinnedDeptId }) {
  const [formData, setFormData] = useState({
    subjectName: "",
    courseCode: "",
    semester: "",
    credits: "",
    academicYearOffered: "",
    deptId: "",
  });
  const [subjectType, setSubjectType] = useState("REGULAR");
  const [eligibleDeptIds, setEligibleDeptIds] = useState([]);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // dept-scoped roles are pinned to their own department, resolved by the shell
  // NOTE: react-hooks/set-state-in-effect flags the setFormData below. Intended and correct —
  // it seeds one field of the *editable* form once the shell resolves the async pin (the rest of
  // formData stays user-edited, deptId resets after each submit). That is initialization of
  // editable state, not pure derivation, so useMemo doesn't apply. A knowing lint error, not disabled.
  useEffect(() => {
    if (deptLocked && pinnedDeptId) {
      setFormData((prev) => ({ ...prev, deptId: pinnedDeptId }));
    }
  }, [deptLocked, pinnedDeptId]);

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  // The academic year is authoritative and stamps the course code's locked two-digit prefix;
  // changing it re-prefixes the code, preserving the suffix.
  const handleYearChange = (e) => {
    const year = e.target.value;
    setFormData((prev) => ({
      ...prev,
      academicYearOffered: year,
      courseCode: buildCourseCode(year, courseCodeSuffix(prev.courseCode)),
    }));
  };

  const toggleEligibleDept = (id) => {
    setEligibleDeptIds((prev) =>
      prev.includes(id) ? prev.filter((d) => d !== id) : [...prev, id],
    );
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    setSuccess("");

    for (const key in formData) {
      if (formData[key] === "") {
        setError("All fields are required.");
        return;
      }
    }
    // a year stamps only the prefix — the admin still enters the suffix
    if (!courseCodeSuffix(formData.courseCode)) {
      setError("Enter the course code.");
      return;
    }
    if (subjectType === "ELECTIVE" && eligibleDeptIds.length === 0) {
      setError("Please select at least one eligible department for an elective subject.");
      return;
    }

    setLoading(true);

    try {
      const payload = {
        ...formData,
        semester: parseInt(formData.semester, 10),
        credits: parseInt(formData.credits, 10),
        academicYearOffered: parseInt(formData.academicYearOffered, 10),
        deptId: parseInt(formData.deptId, 10),
        subjectType,
        eligibleDeptIds: subjectType === "ELECTIVE" ? eligibleDeptIds : [],
      };

      await api.post("/admin/subjects", payload, {
        headers: getAdminHeaders(),
      });

      setSuccess(
        `Subject "${formData.subjectName}" has been added successfully!`,
      );
      setFormData((prev) => ({
        subjectName: "",
        courseCode: "",
        semester: "",
        credits: "",
        academicYearOffered: "",
        // a dept-scoped admin's department stays pinned; everyone else re-picks
        deptId: deptLocked ? prev.deptId : "",
      }));
      setSubjectType("REGULAR");
      setEligibleDeptIds([]);
    } catch (apiError) {
      setError(
        apiError.response?.data?.message ||
          "Failed to add subject. Please try again.",
      );
    } finally {
      setLoading(false);
    }
  };

  const selectedYear = formData.academicYearOffered
    ? Number(formData.academicYearOffered)
    : NaN;
  // recent years for the dropdown, plus the selected one if outside that window
  const baseYears = Array.from(
    { length: 6 },
    (_, i) => new Date().getFullYear() - i + 1,
  );
  const availableYears = Array.from(
    new Set([
      ...baseYears,
      ...(Number.isInteger(selectedYear) ? [selectedYear] : []),
    ]),
  ).sort((a, b) => b - a);

  return (
    <div
      className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-8"
    >
      <h1 className="mb-2 text-2xl font-semibold text-secondary-ink sm:text-3xl">
        Add New Subject
      </h1>
      <p className="mb-6 text-sm text-ink">
        Fill in the details below to add a new backlog subject to the system.
      </p>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="academicYearOffered"
              className="text-xs font-semibold uppercase tracking-[0.08em]"
            >
              Academic Year Offered *
            </label>
            <select
              id="academicYearOffered"
              name="academicYearOffered"
              value={formData.academicYearOffered}
              onChange={handleYearChange}
              className={inputClass}
            >
              <option value="">Select Academic Year</option>
              {availableYears.map((year) => (
                <option key={year} value={year}>
                  {formatAcademicYear(year)}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="subjectName"
              className="text-xs font-semibold uppercase tracking-[0.08em]"
            >
              Subject Name *
            </label>
            <input
              id="subjectName"
              name="subjectName"
              value={formData.subjectName}
              onChange={handleChange}
              className={inputClass}
              placeholder="e.g., Advanced Algorithms"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="courseCode"
              className="text-xs font-semibold uppercase tracking-[0.08em]"
            >
              Course Code *
            </label>
            <CourseCodeField
              id="courseCode"
              year={formData.academicYearOffered}
              value={formData.courseCode}
              onChange={(code) =>
                setFormData((prev) => ({ ...prev, courseCode: code }))
              }
              inputClassName={inputClass}
              dataCy="course-code-suffix"
            />
            <p className="text-xs text-ink-muted">
              The first two digits are set from the academic year.
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="semester"
              className="text-xs font-semibold uppercase tracking-[0.08em]"
            >
              Semester *
            </label>
            <select
              id="semester"
              name="semester"
              value={formData.semester}
              onChange={handleChange}
              className={inputClass}
            >
              <option value="">Select Semester</option>
              {[1, 2, 3, 4, 5, 6, 7, 8].map((sem) => (
                <option key={sem} value={sem}>
                  Semester {sem}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="credits"
              className="text-xs font-semibold uppercase tracking-[0.08em]"
            >
              Credits *
            </label>
            <input
              id="credits"
              name="credits"
              type="number"
              value={formData.credits}
              onChange={handleChange}
              className={inputClass}
              placeholder="e.g., 4"
              min="0"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="deptId"
              className="text-xs font-semibold uppercase tracking-[0.08em]"
            >
              Department *
            </label>
            <select
              id="deptId"
              name="deptId"
              value={formData.deptId}
              onChange={handleChange}
              className={inputClass}
              disabled={departments.length === 0 || deptLocked}
            >
              <option value="">Select Department</option>
              {departments.map((dept) => (
                <option key={dept.id} value={dept.id}>
                  {dept.deptName}
                </option>
              ))}
            </select>
            {deptLocked && adminDepartment && (
              <p className="mt-1 text-xs text-ink-muted">
                Locked to your department: {adminDepartment}
              </p>
            )}
          </div>
        </div>

        {/* Subject type */}
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold uppercase tracking-[0.08em]">
            Subject Type *
          </span>
          <div className="flex gap-3">
            {["REGULAR", "ELECTIVE"].map((type) => (
              <button
                key={type}
                type="button"
                onClick={() => {
                  setSubjectType(type);
                  setEligibleDeptIds([]);
                }}
                className={`rounded-xl border px-4 py-2 text-sm font-semibold transition-colors ${
                  subjectType === type
                    ? "border-primary bg-primary-tint text-primary-ink"
                    : "border-stroke bg-surface-muted text-ink hover:border-primary"
                }`}
              >
                {type.charAt(0) + type.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>

        {/* Eligible departments — only for ELECTIVE */}
        {subjectType === "ELECTIVE" && (
          <div className="flex flex-col gap-2">
            <span className="text-xs font-semibold uppercase tracking-[0.08em]">
              Eligible Departments *
            </span>
            <p className="text-xs text-ink-muted">
              Select which departments' students can register for this elective. The offering department is not included automatically.
            </p>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {departments.map((dept) => {
                const checked = eligibleDeptIds.includes(dept.id);
                const isOfferingDept = String(dept.id) === String(formData.deptId);
                return (
                  <label
                    key={dept.id}
                    className={`flex cursor-pointer items-center gap-3 rounded-xl border p-3 transition-colors ${
                      checked
                        ? "border-primary/40 bg-primary-tint"
                        : "border-stroke bg-surface-muted hover:border-primary/40"
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleEligibleDept(dept.id)}
                      className="h-4 w-4 accent-primary"
                    />
                    <span className="text-sm text-ink">
                      {dept.deptName}
                      {isOfferingDept && (
                        <span className="ml-1.5 text-xs text-ink-muted">
                          (Offering dept)
                        </span>
                      )}
                    </span>
                  </label>
                );
              })}
            </div>
          </div>
        )}

        {error && (
          <AlertBanner tone="error" role="alert" className="mt-4">
            {error}
          </AlertBanner>
        )}

        {success && (
          <AlertBanner tone="success" role="status" className="mt-4">
            {success}
          </AlertBanner>
        )}

        <div className="border-t border-stroke pt-4">
          <MagneticCta
            type="submit"
            disabled={loading}
            className="w-full gap-2 rounded-xl"
            aria-label="Add new subject"
          >
            {loading ? (
              <>
                <LoaderCircle size={16} className="animate-spin" /> Adding
                Subject...
              </>
            ) : (
              <>
                <PlusCircle size={16} /> Add Subject
              </>
            )}
          </MagneticCta>
        </div>
      </form>
    </div>
  );
}

export default AddSubjectTab;
