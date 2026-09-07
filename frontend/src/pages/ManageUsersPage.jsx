import { useState, useEffect, useCallback } from "react";
import {
  KeyRound,
  LoaderCircle,
  PencilLine,
  Trash2,
  UserPlus,
  Users,
  X,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import AdminPageShell from "../components/layout/AdminPageShell";
import PrimaryCta from "../components/ui/PrimaryCta";
import api from "../lib/api";
import { reportLoadError } from "../lib/loadError";
import { findOwnDepartment } from "../lib/session";
import { DEPT_PINNED, ROLE, USER_MANAGEMENT_ROLES } from "../lib/roles";
import { useRoleGuard } from "../hooks/useRoleGuard";
import { FIELD_INPUT } from "../lib/formClasses";
import Field from "../components/ui/Field";
import { btn } from "../lib/buttonClasses";

// Roles each actor may create. The server enforces the same rules; this only shapes the UI. Kept
// as an explicit ladder rather than assembled from lib/roles' subsets — it is page policy keyed by
// actor, not the role vocabulary, and each row must stay readable on its own line.
const CREATABLE_ROLES = {
  ADMIN: ["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR"],
  PRINCIPAL: ["HOD", "DEPT_OFFICE", "PROCTOR"],
  HOD: ["DEPT_OFFICE", "PROCTOR"],
};
const ROLE_LABELS = {
  ADMIN: "Administrator",
  PRINCIPAL: "Principal / Registrar / COE",
  HOD: "Head of Department",
  DEPT_OFFICE: "Department Office",
  PROCTOR: "Proctor",
};

function ManageUsersPage() {
  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";
  const creatableRoles = CREATABLE_ROLES[adminRole] || [];
  // HOD manages only their own department's accounts. The server enforces it; pinning the
  // dropdown just keeps the UI honest.
  const deptLocked = adminRole === ROLE.HOD;
  // Renaming somebody else is ADMIN only, narrower than the create/reset/delete ladder above.
  // Mirrors PATCH /api/admin/users/{username}'s @PreAuthorize, which is the actual control.
  const canRename = adminRole === ROLE.ADMIN;

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

  // Only ADMIN / PRINCIPAL / HOD may be here. The guard redirects; `creatableRoles` still drives
  // WHICH roles the form may create, which is a narrower question than "may I be on this page".
  const canManageUsers = useRoleGuard(USER_MANAGEMENT_ROLES);

  const loadUsers = useCallback(() => {
    setLoading(true);
    api
      .get("/admin/users")
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
    if (DEPT_PINNED.includes(newRole) && !newDeptId) {
      setError("Please select a department for this role.");
      return;
    }
    setCreating(true);
    try {
      const payload = { username: newUsername.trim(), role: newRole };
      if (DEPT_PINNED.includes(newRole)) payload.departmentId = Number(newDeptId);
      const res = await api.post("/admin/users", payload);
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
      const res = await api.post(`/admin/users/${encodeURIComponent(username)}/reset`, {});
      setNotice({ username: res.data.username, label: "Password reset" });
      loadUsers();
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not reset password.");
    } finally {
      setBusyUser("");
    }
  };

  // ADMIN only — PRINCIPAL and HOD are read-only for renames. The server's @PreAuthorize is the
  // control; this only keeps the UI from offering a button that 403s.
  const handleRename = async (username) => {
    const next = window.prompt(`New username for "${username}":`, username);
    if (next === null) return; // cancelled
    const trimmed = next.trim();
    if (!trimmed || trimmed === username) return;

    setError("");
    setBusyUser(username);
    try {
      const res = await api.patch(
        `/admin/users/${encodeURIComponent(username)}`,
        { newUsername: trimmed },
      );
      setNotice({
        username: res.data.username,
        label: "Account renamed",
        renamedFrom: username,
      });
      loadUsers();
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not rename user.");
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
      await api.delete(`/admin/users/${encodeURIComponent(username)}`);
      loadUsers();
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not delete user.");
    } finally {
      setBusyUser("");
    }
  };


  return (
    <AdminPageShell containerClassName="max-w-5xl pb-8">

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
            convention rather than revealing a value that can never be shown again.
            A RENAME must not use that wording — it leaves the password untouched, so the derived
            default (if they are still on one) still matches their OLD name. */}
        {notice && (
          <div
            role="status"
            className="flex items-start justify-between gap-3 rounded-lg bg-primary-tint px-4 py-3 text-sm text-ink"
          >
            {notice.renamedFrom ? (
              <p>
                <strong>{notice.label}.</strong>{" "}
                <strong>{notice.renamedFrom}</strong> is now{" "}
                <strong>{notice.username}</strong>. Their password is unchanged, and they have
                been signed out — they sign back in with the new username.
              </p>
            ) : (
              <p>
                <strong>{notice.label}.</strong> The password for{" "}
                <strong>{notice.username}</strong> is{" "}
                <code className="select-all font-mono">{notice.username}4321</code> — they can
                change it any time from My Password.
              </p>
            )}
            <button
              type="button"
              onClick={() => setNotice(null)}
              aria-label="Dismiss"
              className="rounded-lg p-1.5 text-ink-muted transition-colors hover:bg-surface-muted"
            >
              <X size={16} />
            </button>
          </div>
        )}

        {/* Create user */}
        <section className="py-5 sm:py-6">
          <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-secondary-ink">
            <UserPlus size={18} /> Create New User
          </h2>
          {/* The derived default, stated where the account is made. The dismissible notice below
              reports it for ONE created account; this says it up front for every one. */}
          <p className="mb-4 text-sm text-ink-muted">
            A new account starts on the derived default password,{" "}
            <code className="rounded bg-surface-muted px-1 py-0.5 font-mono text-xs">
              username4321
            </code>
            .
          </p>
          <form
            onSubmit={handleCreate}
            className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 lg:items-end"
          >
            <Field label="Username *" htmlFor="new-username">
              <input
                id="new-username"
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
                className={FIELD_INPUT}
                placeholder="e.g., cse_office"
              />
            </Field>
            <Field label="Role *" htmlFor="new-role">
              <select
                id="new-role"
                value={newRole}
                onChange={(e) => {
                  setNewRole(e.target.value);
                  if (!DEPT_PINNED.includes(e.target.value) && !deptLocked) setNewDeptId("");
                }}
                className={FIELD_INPUT}
              >
                {creatableRoles.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r]}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label={`Department ${DEPT_PINNED.includes(newRole) ? "*" : ""}`}
              htmlFor="new-dept"
            >
              <select
                id="new-dept"
                value={newDeptId}
                onChange={(e) => setNewDeptId(e.target.value)}
                className={FIELD_INPUT}
                disabled={!DEPT_PINNED.includes(newRole) || deptLocked}
              >
                <option value="">
                  {DEPT_PINNED.includes(newRole) ? "Select department" : "Not applicable"}
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
            </Field>
            <PrimaryCta
              type="submit"
              disabled={creating}
              className="w-full gap-2"
              aria-label="Create user"
            >
              {creating ? (
                <LoaderCircle size={16} className="animate-spin" />
              ) : (
                <UserPlus size={16} />
              )}{" "}
              Create
            </PrimaryCta>
          </form>
        </section>

        {/* User list */}
        <section className="py-5 sm:py-6">
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
                              className={btn("neutral", "sm")}
                            >
                              {busy ? (
                                <LoaderCircle size={13} className="animate-spin" />
                              ) : (
                                <KeyRound size={13} />
                              )}{" "}
                              Reset
                            </button>
                            {canRename && (
                              <button
                                type="button"
                                onClick={() => handleRename(u.username)}
                                disabled={busy}
                                className={btn("neutral", "sm")}
                              >
                                <PencilLine size={13} /> Rename
                              </button>
                            )}
                            <button
                              type="button"
                              onClick={() => handleDelete(u.username)}
                              disabled={busy}
                              className={btn("danger", "sm")}
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
    </AdminPageShell>
  );
}

export default ManageUsersPage;
