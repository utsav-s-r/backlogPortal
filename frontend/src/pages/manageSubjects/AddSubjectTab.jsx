import { useState, useEffect } from "react";
import { LoaderCircle, PlusCircle } from "lucide-react";
import PrimaryCta from "../../components/ui/PrimaryCta";
import api from "../../lib/api";
import { formatAcademicYear, recentAcademicYears } from "../../lib/academicYear";
import AlertBanner from "../../components/AlertBanner";
import { FIELD_INPUT, FIELD_LABEL } from "../../lib/formClasses";
import Field from "../../components/ui/Field";
import { btn } from "../../lib/buttonClasses";


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

      await api.post("/admin/subjects", payload);

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

  const availableYears = recentAcademicYears(formData.academicYearOffered);

  return (
    <div
      className="py-5 sm:py-8"
    >
      <h1 className="mb-2 text-2xl font-semibold text-secondary-ink sm:text-3xl">
        Add New Subject
      </h1>
      <p className="mb-6 text-sm text-ink">
        Fill in the details below to add a new backlog subject to the system.
      </p>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Academic Year Offered *" htmlFor="academicYearOffered">
            <select
              id="academicYearOffered"
              name="academicYearOffered"
              value={formData.academicYearOffered}
              onChange={handleChange}
              className={FIELD_INPUT}
            >
              <option value="">Select Academic Year</option>
              {availableYears.map((year) => (
                <option key={year} value={year}>
                  {formatAcademicYear(year)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Subject Name *" htmlFor="subjectName">
            <input
              id="subjectName"
              name="subjectName"
              value={formData.subjectName}
              onChange={handleChange}
              className={FIELD_INPUT}
              placeholder="e.g., Advanced Algorithms"
            />
          </Field>
          <Field label="Course Code *" htmlFor="courseCode">
            <input
              id="courseCode"
              name="courseCode"
              value={formData.courseCode}
              onChange={handleChange}
              className={FIELD_INPUT}
              placeholder="e.g., CSL44"
              data-cy="course-code"
            />
          </Field>
          <Field label="Semester *" htmlFor="semester">
            <select
              id="semester"
              name="semester"
              value={formData.semester}
              onChange={handleChange}
              className={FIELD_INPUT}
            >
              <option value="">Select Semester</option>
              {[1, 2, 3, 4, 5, 6, 7, 8].map((sem) => (
                <option key={sem} value={sem}>
                  Semester {sem}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Credits *" htmlFor="credits">
            <input
              id="credits"
              name="credits"
              type="number"
              value={formData.credits}
              onChange={handleChange}
              className={FIELD_INPUT}
              placeholder="e.g., 4"
              min="0"
            />
          </Field>
          <Field label="Department *" htmlFor="deptId">
            <select
              id="deptId"
              name="deptId"
              value={formData.deptId}
              onChange={handleChange}
              className={FIELD_INPUT}
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
          </Field>
        </div>

        {/* Subject type */}
        <div className="flex flex-col gap-1.5">
          <span className={FIELD_LABEL}>
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
                className={btn(subjectType === type ? "primary" : "neutral")}
              >
                {type.charAt(0) + type.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>

        {/* Eligible departments — only for ELECTIVE */}
        {subjectType === "ELECTIVE" && (
          <div className="flex flex-col gap-2">
            <span className={FIELD_LABEL}>
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
                    className={`flex cursor-pointer items-center gap-3 rounded-lg p-3 transition-colors ${
                      checked
                        ? "bg-primary-tint"
                        : "bg-surface-muted hover:bg-primary-tint"
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
          <PrimaryCta
            type="submit"
            disabled={loading}
            className="w-full gap-2"
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
          </PrimaryCta>
        </div>
      </form>
    </div>
  );
}

export default AddSubjectTab;
