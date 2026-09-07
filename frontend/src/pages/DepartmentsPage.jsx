import { useState, useEffect } from "react";
import { Building2, LoaderCircle, PlusCircle, Save, Trash2 } from "lucide-react";
import AdminPageShell from "../components/layout/AdminPageShell";
import PrimaryCta from "../components/ui/PrimaryCta";
import api from "../lib/api";
import { reportLoadError } from "../lib/loadError";
import AlertBanner from "../components/AlertBanner";
import { UNRESTRICTED } from "../lib/roles";
import { useRoleGuard } from "../hooks/useRoleGuard";
import { FIELD_CONTROL, FIELD_INPUT } from "../lib/formClasses";
import Field from "../components/ui/Field";
import { useArmedConfirm } from "../hooks/useArmedConfirm";
import { btn } from "../lib/buttonClasses";

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
    <AdminPageShell containerClassName="max-w-5xl">
      <h1 className="text-2xl font-semibold text-secondary-ink">Departments</h1>
      <p className="mb-6 mt-1 text-sm text-ink-muted">
        Add departments and keep their codes and contact addresses current. A department that is
        still referenced by a subject, a staff account or a student cannot be deleted.
      </p>

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

      <section className="mb-6 py-5">
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
            <PrimaryCta type="submit" disabled={creating} className="gap-2">
              {creating ? <LoaderCircle size={16} className="animate-spin" /> : <PlusCircle size={16} />}
              {creating ? "Adding..." : "Add Department"}
            </PrimaryCta>
          </div>
        </form>
      </section>

      <section className="py-5">
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
          // A table, not stacked rows: name / code / email are three editable fields per
          // department, and columns line them up so a missing code is visible down the column
          // rather than buried in a row. Row separators are rules — no wrapper box.
          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse text-left text-sm">
              <thead>
                <tr className="border-b border-stroke text-xs uppercase tracking-[0.08em] text-ink-muted">
                  <th className="px-4 py-3 font-semibold">Name</th>
                  <th className="px-4 py-3 font-semibold">Code</th>
                  <th className="px-4 py-3 font-semibold">Contact email</th>
                  <th className="px-4 py-3 text-right font-semibold">Actions</th>
                </tr>
              </thead>
              <tbody>
                {departments.map((d) => (
                  <tr key={d.id} className="border-t border-stroke align-top">
                    <td className="px-4 py-3">
                      <input
                        type="text"
                        data-cy={`dept-name-input-${d.id}`}
                        value={nameEdits[d.id] ?? ""}
                        onChange={(e) =>
                          setNameEdits((prev) => ({ ...prev, [d.id]: e.target.value }))
                        }
                        placeholder="Computer Science & Engineering"
                        aria-label={`Name for ${d.deptName}`}
                        className={`font-semibold ${FIELD_INPUT}`}
                      />
                      {!d.code && (
                        <p className="mt-1 text-xs text-red-600">
                          No code set — students of this branch cannot register.
                        </p>
                      )}
                    </td>
                    <td className="px-4 py-3">
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
                    </td>
                    <td className="px-4 py-3">
                      <input
                        type="email"
                        data-cy={`dept-email-input-${d.id}`}
                        value={emailEdits[d.id] ?? ""}
                        onChange={(e) =>
                          setEmailEdits((prev) => ({ ...prev, [d.id]: e.target.value }))
                        }
                        placeholder="cse@msrit.edu"
                        aria-label={`Contact email for ${d.deptName}`}
                        className={FIELD_INPUT}
                      />
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap items-center justify-end gap-2">
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
                          className={btn("navy", "sm")}
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
                              className={btn("dangerSolid", "sm")}
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
                              className={btn("neutral", "sm")}
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
                            className={btn("danger", "sm")}
                          >
                            <Trash2 size={14} />
                            Delete
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </AdminPageShell>
  );
}

export default DepartmentsPage;
