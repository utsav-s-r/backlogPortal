import { useState } from "react";
import { ArrowLeft, LoaderCircle, Lock, UserPen } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import AdminLayout from "../components/layout/AdminLayout";
import PrimaryCta from "../components/ui/PrimaryCta";
import api, { clearAdminSession } from "../lib/api";
import AlertBanner from "../components/AlertBanner";
import { FIELD_INPUT } from "../lib/formClasses";
import Field from "../components/ui/Field";
import { btn } from "../lib/buttonClasses";

// Self-service account settings for a signed-in admin-type user: password, and — since V4 made
// usernames renamable — their own username. Nothing forces them here; accounts start on the derived
// default (username + "4321") and stay on it until they choose otherwise, so every role reaches
// this from the sidebar's "My password" row.
function ChangePasswordPage() {
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  // Rename form. Its own error slot, as ManageUsersPage keeps departmentsError separate: one
  // shared slot lets whichever form submitted last silently clear the other's message.
  const [newUsername, setNewUsername] = useState("");
  const [renamePassword, setRenamePassword] = useState("");
  const [renameError, setRenameError] = useState("");
  const [renaming, setRenaming] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");

    if (!currentPassword || !newPassword) {
      setError("All fields are required.");
      return;
    }
    if (newPassword.length < 8) {
      setError("New password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setError("New password and confirmation do not match.");
      return;
    }

    setLoading(true);
    try {
      await api.post("/auth/change-password", { currentPassword, newPassword });
      navigate("/admin");
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not change password.");
    } finally {
      setLoading(false);
    }
  };

  // Renaming yourself ENDS the session: the JWT subject is the username, so the server clears the
  // cookie and the token stops resolving. Nothing to recover from — sign back in under the new
  // name. The password is deliberately left alone, so an account still on the derived default
  // keeps `oldusername4321`.
  const handleRename = async (e) => {
    e.preventDefault();
    setRenameError("");

    if (!renamePassword || !newUsername.trim()) {
      setRenameError("All fields are required.");
      return;
    }
    if (newUsername.trim().length < 4) {
      setRenameError("Username must be at least 4 characters.");
      return;
    }

    setRenaming(true);
    try {
      await api.post(
        "/auth/change-username",
        { currentPassword: renamePassword, newUsername: newUsername.trim() },
      );
      // The cookie is already gone server-side; drop the cached identity too, or the login page
      // would still show a stale adminRole/adminUsername.
      clearAdminSession();
      navigate("/admin/login");
    } catch (apiError) {
      setRenameError(apiError.response?.data?.message || "Could not change username.");
    } finally {
      setRenaming(false);
    }
  };

  return (
    <AdminLayout>
      <div className="mx-auto w-full max-w-3xl px-4 py-10 sm:px-6 lg:px-8">
        <div className="mb-6 text-left">
          <h1 className="text-3xl font-semibold text-secondary-ink">Account Settings</h1>
          <p className="mt-2 text-sm text-ink">
            Update your own sign-in details. Role and department are set by an administrator and
            cannot be changed here.
          </p>
        </div>

        <h2 className="mb-4 text-xl font-semibold text-secondary-ink">Change Password</h2>

        <form onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label="Current Password" htmlFor="current-password" labelClassName="text-ink">
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              className={FIELD_INPUT}
              placeholder="Enter current password"
              autoComplete="current-password"
            />
          </Field>

          <Field label="New Password" htmlFor="new-password" labelClassName="text-ink">
            <input
              id="new-password"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className={FIELD_INPUT}
              placeholder="At least 8 characters"
              autoComplete="new-password"
            />
          </Field>

          <Field label="Confirm New Password" htmlFor="confirm-password" labelClassName="text-ink">
            <input
              id="confirm-password"
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              className={FIELD_INPUT}
              placeholder="Re-enter new password"
              autoComplete="new-password"
            />
          </Field>

          </div>

          {error && (
            <AlertBanner tone="error" role="alert" className="mt-4">
              {error}
            </AlertBanner>
          )}

          <PrimaryCta
            type="submit"
            disabled={loading}
            className="mt-4 gap-2"
            aria-label="Change password"
          >
            {loading ? (
              <LoaderCircle size={16} className="animate-spin" />
            ) : (
              <Lock size={16} />
            )}{" "}
            Save New Password
          </PrimaryCta>
        </form>

        <hr className="my-7 border-stroke" />

        <div className="mb-4 text-left">
          <h2 className="text-xl font-semibold text-secondary-ink">Change Username</h2>
          {/* Stated up front, not discovered: the username is the session identity, so changing it
              necessarily ends the session. */}
          <p className="mt-2 text-sm text-ink">
            You sign in with this name. Changing it{" "}
            <strong>signs you out immediately</strong> — sign back in with the new username. Your
            password does not change.
          </p>
        </div>

        <form onSubmit={handleRename}>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="New Username" htmlFor="new-username" labelClassName="text-ink">
            <input
              id="new-username"
              type="text"
              value={newUsername}
              onChange={(e) => setNewUsername(e.target.value)}
              className={FIELD_INPUT}
              placeholder="At least 4 characters"
              autoComplete="username"
            />
          </Field>

          <Field label="Current Password" htmlFor="rename-password" labelClassName="text-ink">
            <input
              id="rename-password"
              type="password"
              value={renamePassword}
              onChange={(e) => setRenamePassword(e.target.value)}
              className={FIELD_INPUT}
              placeholder="Confirm with your password"
              autoComplete="current-password"
            />
          </Field>

          </div>

          {renameError && (
            <AlertBanner tone="error" role="alert" className="mt-4">
              {renameError}
            </AlertBanner>
          )}

          <PrimaryCta
            type="submit"
            disabled={renaming}
            className="mt-4 gap-2"
            aria-label="Change username"
          >
            {renaming ? (
              <LoaderCircle size={16} className="animate-spin" />
            ) : (
              <UserPen size={16} />
            )}{" "}
            Save New Username
          </PrimaryCta>
        </form>

        <div className="mt-4 text-center">
          <Link
            to="/admin"
            className={btn()}
          >
            <ArrowLeft size={14} /> Back to dashboard
          </Link>
        </div>
      </div>
    </AdminLayout>
  );
}

export default ChangePasswordPage;
