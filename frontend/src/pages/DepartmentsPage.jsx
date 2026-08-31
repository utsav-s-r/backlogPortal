import { useState, useEffect } from "react";
import { ArrowLeft, Building2, LoaderCircle, PlusCircle, Save, Trash2 } from "lucide-react";
import { Link } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";
import api from "../lib/api";
import { reportLoadError } from "../lib/loadError";
import AlertBanner from "../components/AlertBanner";
import { UNRESTRICTED } from "../lib/roles";
import { useRoleGuard } from "../hooks/useRoleGuard";
import { FIELD_CONTROL } from "../lib/formClasses";
import Field from "../components/ui/Field";
import HeaderPill from "../components/ui/HeaderPill";
import { useArmedConfirm } from "../hooks/useArmedConfirm";

function DepartmentsPage() {
  // Department writes are ADMIN/PRINCIPAL only; HOD and DEPT_OFFICE are read-only here and are
  // NOT dept-pinned for writes. Enforced server-side by @PreAuthorize on /api/admin/departments.
  const allowed = useRoleGuard(UNRESTRICTED);

  const [departments, setDepartments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // new department form
  const [deptName, setDeptName] = useState("");
  const [code, setCode] = useState("");
  const [contactEmail, setContactEmail] = useState("");
  const [creating, setCreating] = useState(false);

  // inline name + code + email edits keyed by department id
  const [nameEdits, setNameEdits] = useState({});
  const [codeEdits, setCodeEdits] = useState({});
  const [emailEdits, setEmailEdits] = useState({});
  const [savingId, setSavingId] = useState(null);

  // two-step delete: first click arms the confirm, second click deletes
  const confirmDelete = useArmedConfirm();
  const [deletingId, setDeletingId] = useState(null);

  const loadDepartments = () => {
    setLoading(true);
    api
      .get("/admin/departments")
      .then((res) => {
        setDepartments(res.data);
        setNameEdits(
          res.data.reduce((acc, d) => ({ ...acc, [d.id]: d.deptName || "" }), {}),
        );
        setCodeEdits(
          res.data.reduce((acc, d) => ({ ...acc, [d.id]: d.code || "" }), {}),
        );
        setEmailEdits(
          res.data.reduce((acc, d) => ({ ...acc, [d.id]: d.contactEmail || "" }), {}),
        );
        setLoading(false);
      })
      // no .finally: on a 401 the spinner must stay up until the redirect lands
      .catch((err) => {
        console.error("Failed to load departments", err);
        if (reportLoadError(err, setError, "Could not load departments.")) setLoading(false);
      });
  };

  useEffect(() => {
    if (!allowed) return;
    // NOTE: react-hooks/set-state-in-effect flags this (loadDepartments setStates internally).
    // Intended and correct — fetch-on-mount into an external system, state lands in the async
    // .then/.finally. A knowing lint error, deliberately not disabled.
    loadDepartments();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleCreate = async (e) => {
    e.preventDefault();
    setError("");
    setSuccess("");
    if (!deptName.trim()) {
      setError("Department name is required.");
      return;
    }
    if (!/^[A-Za-z]{2}$/.test(code.trim())) {
      setError("Department code must be exactly 2 letters.");
      return;
    }
    setCreating(true);
    try {
      await api.post(
        "/admin/departments",
        {
          deptName: deptName.trim(),
          code: code.trim().toUpperCase(),
          contactEmail: contactEmail.trim() || null,
        },
      );
      setSuccess(`Department "${deptName.trim()}" added.`);
      setDeptName("");
      setCode("");
      setContactEmail("");
      loadDepartments();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to add department.");
    } finally {
      setCreating(false);
    }
  };

  const handleSaveRow = async (dept) => {
    const nextName = (nameEdits[dept.id] || "").trim();
    const nextCode = (codeEdits[dept.id] || "").trim();
    const nextEmail = (emailEdits[dept.id] || "").trim();
    setError("");
    setSuccess("");
    if (!nextName) {
      setError(`Department name for ${dept.deptName} cannot be blank.`);
      return;
    }
    if (!/^[A-Za-z]{2}$/.test(nextCode)) {
      setError(`Code for ${dept.deptName} must be exactly 2 letters.`);
      return;
    }
    // Email is optional and sanity-checked client-side when given (the server also enforces
    // @Email). A blank field clears the address, sent as null.
    if (nextEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(nextEmail)) {
      setError(`Contact email for ${dept.deptName} must be a valid address.`);
      return;
    }
    setSavingId(dept.id);
    try {
      await api.put(
        `/admin/departments/${dept.id}`,
        {
          deptName: nextName,
          code: nextCode.toUpperCase(),
          contactEmail: nextEmail || null,
          // version the row was loaded at, so the server can reject a stale overwrite if
          // another admin saved this department meanwhile
          version: dept.version,
        },
      );
      setSuccess(`Department "${dept.deptName}" saved.`);
      loadDepartments();
    } catch (err) {
      // 409 = edited underneath us; resync so the admin sees the current value and version
      // before retrying
      if (err.response?.status === 409) {
        setError(
          err.response?.data?.message ||
            "This department was changed by someone else. The list has been refreshed.",
        );
        loadDepartments();
      } else {
        setError(err.response?.data?.message || "Failed to save department.");
      }
    } finally {
      setSavingId(null);
    }
  };

  const handleDelete = async (dept) => {
    setError("");
    setSuccess("");
    setDeletingId(dept.id);
    try {
      await api.delete(`/admin/departments/${dept.id}`);
      setSuccess(`Department "${dept.deptName}" deleted.`);
      confirmDelete.disarm();
      loadDepartments();
    } catch (err) {
      // 409 = still referenced by subjects / users / students; surface the server's specific
      // reason so the admin knows what to clear first
      setError(err.response?.data?.message || "Failed to delete department.");
    } finally {
      setDeletingId(null);
    }
  };

  if (!allowed) return null;

  return (
    <div className="min-h-screen bg-surface-1 px-4 py-8 text-ink sm:px-6 lg:px-8">
      <div className="mx-auto w-full max-w-3xl">
        <BrandHeader
          className="mb-6"
          badge={
            <p className="mt-2 inline-flex rounded-full border border-white/25 bg-white/10 px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.12em] text-white">
              Manage Departments
            </p>
          }
        >
          <HeaderPill as={Link} to="/admin">
            <ArrowLeft size={14} /> Dashboard
          </HeaderPill>
        </BrandHeader>

        {error && (
          <AlertBanner
            tone="error"
            data-cy="dept-error"
            role="alert"
            className="mb-4"
          >
            {error}
          </AlertBanner>
        )}
        {success && (
          <AlertBanner tone="success" className="mb-4">
            {success}
          </AlertBanner>
        )}

        <section className="mb-6 rounded-2xl border border-stroke bg-surface-1 p-5 shadow-soft">
          <h3 className="mb-1 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
            <PlusCircle size={18} /> New Department
          </h3>
          <p className="mb-4 text-xs text-ink-muted">
            The 2-letter code must match the branch segment of the USN (e.g. "CS" in 1MS22CS001).
          </p>
          <form onSubmit={handleCreate} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Department Name *" htmlFor="dept-name">
              <input
                id="dept-name"
                type="text"
                value={deptName}
                onChange={(e) => setDeptName(e.target.value)}
                placeholder="e.g. Computer Science"
                className={FIELD_CONTROL}
              />
            </Field>
            <Field label="Code (2 letters) *" htmlFor="dept-code">
              <input
                id="dept-code"
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase().slice(0, 2))}
                placeholder="e.g. CS"
                maxLength={2}
                className={`${FIELD_CONTROL} uppercase`}
              />
            </Field>
            <Field label="Contact Email" htmlFor="dept-email" className="sm:col-span-2">
              <input
                id="dept-email"
                type="email"
                value={contactEmail}
                onChange={(e) => setContactEmail(e.target.value)}
                placeholder="e.g. cse@msrit.edu"
                className={FIELD_CONTROL}
              />
            </Field>
            <div className="sm:col-span-2">
              <MagneticCta type="submit" disabled={creating} className="gap-2 rounded-xl">
                {creating ? <LoaderCircle size={16} className="animate-spin" /> : <PlusCircle size={16} />}
                {creating ? "Adding..." : "Add Department"}
              </MagneticCta>
            </div>
          </form>
        </section>

        <section className="rounded-2xl border border-stroke bg-surface-1 p-5 shadow-soft">
          <h3 className="mb-4 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
            <Building2 size={18} /> Departments
          </h3>
          {loading ? (
            <p className="inline-flex items-center gap-2 text-sm">
              <LoaderCircle size={16} className="animate-spin" /> Loading...
            </p>
          ) : departments.length === 0 ? (
            <p className="text-sm text-ink-muted">No departments yet. Add one above.</p>
          ) : (
            <ul className="space-y-3">
              {departments.map((d) => (
                <li
                  key={d.id}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-stroke bg-surface-muted px-4 py-3"
                >
                  <div className="min-w-0">
                    <input
                      type="text"
                      data-cy={`dept-name-input-${d.id}`}
                      value={nameEdits[d.id] ?? ""}
                      onChange={(e) =>
                        setNameEdits((prev) => ({ ...prev, [d.id]: e.target.value }))
                      }
                      placeholder="Computer Science & Engineering"
                      aria-label={`Name for ${d.deptName}`}
                      className={`w-64 font-semibold ${FIELD_CONTROL}`}
                    />
                    {!d.code && (
                      <p className="mt-1 text-xs text-red-600">No code set — students of this branch cannot register.</p>
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      type="email"
                      data-cy={`dept-email-input-${d.id}`}
                      value={emailEdits[d.id] ?? ""}
                      onChange={(e) =>
                        setEmailEdits((prev) => ({ ...prev, [d.id]: e.target.value }))
                      }
                      placeholder="cse@msrit.edu"
                      aria-label={`Contact email for ${d.deptName}`}
                      className={`w-48 ${FIELD_CONTROL}`}
                    />
                    <input
                      type="text"
                      data-cy={`dept-code-input-${d.id}`}
                      value={codeEdits[d.id] ?? ""}
                      onChange={(e) =>
                        setCodeEdits((prev) => ({
                          ...prev,
                          [d.id]: e.target.value.toUpperCase().slice(0, 2),
                        }))
                      }
                      placeholder="CS"
                      maxLength={2}
                      aria-label={`Code for ${d.deptName}`}
                      className={`w-16 text-center uppercase ${FIELD_CONTROL}`}
                    />
                    <button
                      type="button"
                      data-cy={`dept-save-${d.id}`}
                      onClick={() => handleSaveRow(d)}
                      disabled={
                        savingId === d.id ||
                        ((nameEdits[d.id] || "") === (d.deptName || "") &&
                          (codeEdits[d.id] || "") === (d.code || "") &&
                          (emailEdits[d.id] || "") === (d.contactEmail || ""))
                      }
                      className="inline-flex items-center gap-1.5 rounded-lg bg-secondary px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-primary disabled:opacity-50"
                    >
                      {savingId === d.id ? (
                        <LoaderCircle size={14} className="animate-spin" />
                      ) : (
                        <Save size={14} />
                      )}
                      Save
                    </button>
                    {confirmDelete.isArmed(d.id) ? (
                      <>
                        <button
                          type="button"
                          data-cy={`dept-delete-confirm-${d.id}`}
                          onClick={() => handleDelete(d)}
                          disabled={deletingId === d.id}
                          className="inline-flex items-center gap-1.5 rounded-lg bg-red-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-50"
                        >
                          {deletingId === d.id ? (
                            <LoaderCircle size={14} className="animate-spin" />
                          ) : (
                            <Trash2 size={14} />
                          )}
                          Confirm
                        </button>
                        <button
                          type="button"
                          data-cy={`dept-delete-cancel-${d.id}`}
                          onClick={() => confirmDelete.disarm()}
                          disabled={deletingId === d.id}
                          className="inline-flex items-center rounded-lg border border-stroke px-3 py-2 text-xs font-semibold text-ink transition-colors hover:bg-surface-1 disabled:opacity-50"
                        >
                          Cancel
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        data-cy={`dept-delete-${d.id}`}
                        onClick={() => {
                          setError("");
                          setSuccess("");
                          confirmDelete.arm(d.id);
                        }}
                        aria-label={`Delete ${d.deptName}`}
                        className="inline-flex items-center gap-1.5 rounded-lg border border-red-200 px-3 py-2 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50"
                      >
                        <Trash2 size={14} />
                        Delete
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}

export default DepartmentsPage;
