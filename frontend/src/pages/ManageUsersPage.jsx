import { useState, useEffect, useCallback } from "react";
import {
  ArrowLeft,
  KeyRound,
  LoaderCircle,
  Trash2,
  UserPlus,
  Users,
  X,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link, useNavigate } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";
import api, { getAdminHeaders } from "../lib/api";
import { reportLoadError } from "../lib/loadError";
import { findOwnDepartment } from "../lib/session";

// Roles each actor may create. The server enforces the same rules; this only shapes the UI.
const CREATABLE_ROLES = {
  ADMIN: ["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR"],
  PRINCIPAL: ["HOD", "DEPT_OFFICE", "PROCTOR"],
  HOD: ["DEPT_OFFICE", "PROCTOR"],
};
const DEPT_ROLES = new Set(["HOD", "DEPT_OFFICE", "PROCTOR"]);
const ROLE_LABELS = {
  ADMIN: "Administrator",
  PRINCIPAL: "Principal / Registrar / COE",
  HOD: "Head of Department",
  DEPT_OFFICE: "Department Office",
  PROCTOR: "Proctor",
};

function ManageUsersPage() {
  const navigate = useNavigate();
  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";
  const creatableRoles = CREATABLE_ROLES[adminRole] || [];
  // HOD manages only their own department's accounts. The server enforces it; pinning the
  // dropdown just keeps the UI honest.
  const deptLocked = adminRole === "HOD";

  const [users, setUsers] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  // Separate from `error`, as AdminLoginPage does: a failed departments fetch is not a user action
  // failing, must survive the setError("") starting every submit, and sharing one slot let the
  // second of the two fetches silently win.
  const [departmentsError, setDepartmentsError] = useState("");

  // Create form
  const [newUsername, setNewUsername] = useState("");
  const [newRole, setNewRole] = useState(creatableRoles[0] || "");
  const [newDeptId, setNewDeptId] = useState("");
  const [creating, setCreating] = useState(false);

  // One-time temp-password reveal + pending action state
  const [notice, setNotice] = useState(null); // { username, label }
  const [busyUser, setBusyUser] = useState(""); // username currently being reset/deleted

  // Only ADMIN / PRINCIPAL / HOD may be here — the redirect below and the fetch guard share this.
  const canManageUsers = creatableRoles.length > 0;

  useEffect(() => {
    if (!canManageUsers) {
      navigate("/admin");
    }
  }, [canManageUsers, navigate]);

  const loadUsers = useCallback(() => {
    setLoading(true);
    api
      .get("/admin/users", { headers: getAdminHeaders() })
      .then((res) => {
        setUsers(res.data);
        setLoading(false);
      })
      // no .finally: on a 401 the spinner must stay up until the redirect lands
      .catch((err) => {
        if (reportLoadError(err, setError, "Could not load users.")) setLoading(false);
      });
  }, []);

  useEffect(() => {
    // Roles with nothing to manage are redirected by the effect above, which commits in the same
    // pass — fetching regardless fired a guaranteed 403 and flashed its error banner before the
    // redirect landed. Guarded inside the effect, as DepartmentsPage/ExamCyclePage do.
    if (!canManageUsers) return;
    // NOTE: react-hooks/set-state-in-effect flags this (loadUsers setStates internally).
    // Intended and correct — fetch-on-mount into an external system, state lands in the async
    // .then/.finally. A knowing lint error, deliberately not disabled.
    loadUsers();
    api
      .get("/departments")
      .then((res) => setDepartments(res.data))
      .catch((err) =>
        reportLoadError(
          err,
          setDepartmentsError,
          "Could not load departments. Creating a department-scoped user may be unavailable.",
        ),
      );
  }, [loadUsers, canManageUsers]);

  // pin the department to the HOD's own once departments load
  // NOTE: react-hooks/set-state-in-effect flags the setNewDeptId below. Intended and correct —
  // it seeds an *editable* create-form field once the async departments list arrives (still
  // changeable when not dept-locked, reset after each create). That is initialization of editable
  // state, not pure derivation, so useMemo doesn't apply. A knowing lint error, not disabled.
  useEffect(() => {
    if (deptLocked && adminDepartment && departments.length > 0) {
      const myDept = findOwnDepartment(departments, adminDepartment);
      if (myDept) {
        setNewDeptId(String(myDept.id));
      }
    }
  }, [departments, deptLocked, adminDepartment]);

  const handleCreate = async (e) => {
    e.preventDefault();
    setError("");
    if (!newUsername.trim()) {
      setError("Username is required.");
      return;
    }
    if (DEPT_ROLES.has(newRole) && !newDeptId) {
      setError("Please select a department for this role.");
      return;
    }
    setCreating(true);
    try {
      const payload = { username: newUsername.trim(), role: newRole };
      if (DEPT_ROLES.has(newRole)) payload.departmentId = Number(newDeptId);
      const res = await api.post("/admin/users", payload, {
        headers: getAdminHeaders(),
      });
      setNotice({ username: res.data.username, label: "Account created" });
      setNewUsername("");
      if (!deptLocked) setNewDeptId("");
      setNewRole(creatableRoles[0] || "");
      loadUsers();
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not create user.");
    } finally {
      setCreating(false);
    }
  };

  const handleReset = async (username) => {
    setError("");
    setBusyUser(username);
    try {
      const res = await api.post(
        `/admin/users/${encodeURIComponent(username)}/reset`,
        {},
        { headers: getAdminHeaders() },
      );
      setNotice({ username: res.data.username, label: "Password reset" });
      loadUsers();
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not reset password.");
    } finally {
      setBusyUser("");
    }
  };

  const handleDelete = async (username) => {
    if (!window.confirm(`Delete user "${username}"? This cannot be undone.`)) {
      return;
    }
    setError("");
    setBusyUser(username);
    try {
      await api.delete(`/admin/users/${encodeURIComponent(username)}`, {
        headers: getAdminHeaders(),
      });
      loadUsers();
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not delete user.");
    } finally {
      setBusyUser("");
    }
  };

  const inputClass =
    "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring";

  return (
    <div className="min-h-screen bg-surface-1 px-4 py-8 text-ink sm:px-6 lg:px-8">
      <div className="mx-auto w-full max-w-5xl pb-8">
        <BrandHeader className="mb-6">
          <div className="flex gap-2">
            <Link
              to="/admin/change-password"
              className="inline-flex items-center gap-1 rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
            >
              <KeyRound size={15} className="mr-0.5" /> My Password
            </Link>
            <Link
              to="/admin"
              aria-label="Back to admin dashboard"
              className="inline-flex items-center rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
            >
              <ArrowLeft size={15} className="mr-1" /> Dashboard
            </Link>
          </div>
        </BrandHeader>

        <div
          className="space-y-6"
        >
          <div>
            <h1 className="mb-1 flex items-center gap-2 text-2xl font-semibold text-secondary-ink sm:text-3xl">
              <Users size={26} /> Users
            </h1>
            <p className="text-sm text-ink">
              Create, reset, and remove staff accounts you're authorised to
              manage.
            </p>
          </div>

          {error && (
            <AlertBanner tone="error" role="alert">
              {error}
            </AlertBanner>
          )}

          {/* Its own banner: the create form below depends on this list, and folding it into
              `error` let a user action's message overwrite it (or vice versa). */}
          {departmentsError && (
            <AlertBanner
              tone="warning"
              role="alert"
              data-cy="users-departments-error"
            >
              {departmentsError}
            </AlertBanner>
          )}

          {/* No secret to transport: the password is derived from the username, so this states the
              convention rather than revealing a value that can never be shown again. */}
          {notice && (
            <div
              role="status"
              className="flex items-start justify-between gap-3 rounded-xl border border-stroke bg-primary-tint px-4 py-3 text-sm text-ink"
            >
              <p>
                <strong>{notice.label}.</strong> The password for{" "}
                <strong>{notice.username}</strong> is{" "}
                <code className="select-all font-mono">{notice.username}4321</code> — they can
                change it any time from My Password.
              </p>
              <button
                type="button"
                onClick={() => setNotice(null)}
                aria-label="Dismiss"
                className="rounded-full p-1 text-ink-muted hover:bg-surface-muted"
              >
                <X size={16} />
              </button>
            </div>
          )}

          {/* Create user */}
          <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
            <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-secondary-ink">
              <UserPlus size={18} /> Create New User
            </h2>
            <form
              onSubmit={handleCreate}
              className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 lg:items-end"
            >
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="new-username"
                  className="text-xs font-semibold uppercase tracking-[0.08em]"
                >
                  Username *
                </label>
                <input
                  id="new-username"
                  value={newUsername}
                  onChange={(e) => setNewUsername(e.target.value)}
                  className={inputClass}
                  placeholder="e.g., cse_office"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="new-role"
                  className="text-xs font-semibold uppercase tracking-[0.08em]"
                >
                  Role *
                </label>
                <select
                  id="new-role"
                  value={newRole}
                  onChange={(e) => {
                    setNewRole(e.target.value);
                    if (!DEPT_ROLES.has(e.target.value) && !deptLocked) setNewDeptId("");
                  }}
                  className={inputClass}
                >
                  {creatableRoles.map((r) => (
                    <option key={r} value={r}>
                      {ROLE_LABELS[r]}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="new-dept"
                  className="text-xs font-semibold uppercase tracking-[0.08em]"
                >
                  Department {DEPT_ROLES.has(newRole) ? "*" : ""}
                </label>
                <select
                  id="new-dept"
                  value={newDeptId}
                  onChange={(e) => setNewDeptId(e.target.value)}
                  className={inputClass}
                  disabled={!DEPT_ROLES.has(newRole) || deptLocked}
                >
                  <option value="">
                    {DEPT_ROLES.has(newRole) ? "Select department" : "Not applicable"}
                  </option>
                  {(deptLocked
                    ? departments.filter((d) => d.id === findOwnDepartment(departments, adminDepartment)?.id)
                    : departments
                  ).map((d) => (
                    <option key={d.id} value={String(d.id)}>
                      {d.deptName}
                    </option>
                  ))}
                </select>
              </div>
              <MagneticCta
                type="submit"
                disabled={creating}
                className="w-full gap-2 rounded-xl"
                aria-label="Create user"
              >
                {creating ? (
                  <LoaderCircle size={16} className="animate-spin" />
                ) : (
                  <UserPlus size={16} />
                )}{" "}
                Create
              </MagneticCta>
            </form>
          </section>

          {/* User list */}
          <section className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6">
            <h2 className="mb-4 text-lg font-semibold text-secondary-ink">
              Existing Users
            </h2>

            {loading ? (
              <div className="flex items-center gap-2 py-8 text-sm text-ink-muted">
                <LoaderCircle size={16} className="animate-spin" /> Loading
                users…
              </div>
            ) : users.length === 0 ? (
              // "none exist" holds only if the fetch succeeded; with `error` set the list is
              // unknown, and an empty result beside the failure reads as a permissions verdict the
              // server never gave.
              <p className="py-8 text-center text-sm text-ink-muted">
                {error ? "Users could not be loaded." : "No users you can manage yet."}
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] border-collapse text-sm">
                  <thead>
                    <tr className="border-b border-stroke text-left text-xs uppercase tracking-[0.08em] text-ink-muted">
                      <th className="py-2.5 pr-4 font-semibold">Username</th>
                      <th className="py-2.5 pr-4 font-semibold">Role</th>
                      <th className="py-2.5 pr-4 font-semibold">Department</th>
                      <th className="py-2.5 pr-4 text-right font-semibold">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.map((u) => {
                      const busy = busyUser === u.username;
                      return (
                        <tr
                          key={u.username}
                          className="border-b border-stroke last:border-0"
                        >
                          <td className="py-3 pr-4 font-medium text-ink">
                            {u.username}
                          </td>
                          <td className="py-3 pr-4">
                            <span className="inline-flex rounded-full bg-surface-muted px-2.5 py-1 text-xs font-medium">
                              {ROLE_LABELS[u.role] || u.role}
                            </span>
                          </td>
                          <td className="py-3 pr-4 text-ink-muted">
                            {u.departmentName || "—"}
                          </td>
                          <td className="py-3 pr-4">
                            <div className="flex items-center justify-end gap-2">
                              <button
                                type="button"
                                onClick={() => handleReset(u.username)}
                                disabled={busy}
                                className="inline-flex items-center gap-1 rounded-lg border border-stroke px-2.5 py-1.5 text-xs font-semibold text-ink transition-colors hover:border-primary hover:text-primary-ink disabled:opacity-50"
                              >
                                {busy ? (
                                  <LoaderCircle size={13} className="animate-spin" />
                                ) : (
                                  <KeyRound size={13} />
                                )}{" "}
                                Reset
                              </button>
                              <button
                                type="button"
                                onClick={() => handleDelete(u.username)}
                                disabled={busy}
                                className="inline-flex items-center gap-1 rounded-lg border border-red-200 px-2.5 py-1.5 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50 disabled:opacity-50"
                              >
                                <Trash2 size={13} /> Delete
                              </button>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      </div>

    </div>
  );
}

export default ManageUsersPage;
