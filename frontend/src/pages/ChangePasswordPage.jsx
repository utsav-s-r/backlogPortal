import { useState } from "react";
import { ArrowLeft, KeyRound, LoaderCircle, Lock } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import BrandIdentity from "../components/layout/BrandIdentity";
import MagneticCta from "../components/ui/MagneticCta";
import api, { getAdminHeaders } from "../lib/api";
import AlertBanner from "../components/AlertBanner";

// One flow: a signed-in admin-type user changing their own password. Nothing forces them here —
// accounts start on the derived default (username + "4321") and stay on it until they choose
// otherwise, so every role reaches this from the dashboard header.
function ChangePasswordPage() {
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

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
      await api.post(
        "/auth/change-password",
        { currentPassword, newPassword },
        { headers: getAdminHeaders() },
      );
      navigate("/admin");
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Could not change password.");
    } finally {
      setLoading(false);
    }
  };

  const inputClass =
    "w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring";

  return (
    <div className="min-h-screen bg-surface-1 px-4 py-10 sm:px-6 lg:px-8">
      <div
        className="mx-auto w-full max-w-md rounded-3xl border border-stroke bg-surface-1 p-6 shadow-soft sm:p-8"
      >
        <div className="mb-6 text-left">
          <BrandIdentity compact onSurface />
          <p className="mb-2 mt-4 inline-flex items-center gap-1.5 rounded-full border border-primary/30 bg-surface-muted px-3 py-1 text-xs font-semibold uppercase tracking-[0.12em] text-primary-ink">
            <KeyRound size={12} /> Change Password
          </p>
          <h1 className="text-3xl font-semibold text-secondary-ink">Change Password</h1>
          <p className="mt-2 text-sm text-ink">Update the password for your account.</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="current-password"
              className="text-xs font-semibold uppercase tracking-[0.08em] text-ink"
            >
              Current Password
            </label>
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              className={inputClass}
              placeholder="Enter current password"
              autoComplete="current-password"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="new-password"
              className="text-xs font-semibold uppercase tracking-[0.08em] text-ink"
            >
              New Password
            </label>
            <input
              id="new-password"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className={inputClass}
              placeholder="At least 8 characters"
              autoComplete="new-password"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="confirm-password"
              className="text-xs font-semibold uppercase tracking-[0.08em] text-ink"
            >
              Confirm New Password
            </label>
            <input
              id="confirm-password"
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              className={inputClass}
              placeholder="Re-enter new password"
              autoComplete="new-password"
            />
          </div>

          {error && (
            <AlertBanner tone="error" role="alert">
              {error}
            </AlertBanner>
          )}

          <MagneticCta
            type="submit"
            disabled={loading}
            className="mt-2 w-full gap-2 rounded-xl"
            aria-label="Change password"
          >
            {loading ? (
              <LoaderCircle size={16} className="animate-spin" />
            ) : (
              <Lock size={16} />
            )}{" "}
            Save New Password
          </MagneticCta>
        </form>

        <div className="mt-4 text-center">
          <Link
            to="/admin"
            className="inline-flex items-center gap-1 text-sm font-medium text-secondary-ink underline-offset-4 hover:underline"
          >
            <ArrowLeft size={14} /> Back to dashboard
          </Link>
        </div>
      </div>
    </div>
  );
}

export default ChangePasswordPage;
