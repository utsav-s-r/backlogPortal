import { useState, useEffect, useCallback } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Download,
  LoaderCircle,
  LogOut,
  Pencil,
  Phone,
  Check,
  X,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link, useNavigate } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";
import ThemeToggle from "../components/ui/ThemeToggle";
import api, { getStudentHeaders, logoutStudent } from "../lib/api";
import { saveBlob, readBlobErrorMessage } from "../lib/download";

function statusBadgeClass(status) {
  if (status === "VERIFIED")
    return "border-primary/30 bg-primary-tint text-primary-ink";
  if (status === "REJECTED") return "border-red-200 bg-red-50 text-red-600";
  return "border-stroke bg-surface-muted text-ink";
}

function StudentDashboardPage() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [registrations, setRegistrations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [editingPhone, setEditingPhone] = useState(false);
  const [phoneInput, setPhoneInput] = useState("");
  const [savingPhone, setSavingPhone] = useState(false);
  const [phoneError, setPhoneError] = useState("");

  const [downloadingId, setDownloadingId] = useState("");
  // per-row, so the message sits next to the form it failed for rather than in a page-level banner
  const [downloadError, setDownloadError] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [meRes, regRes] = await Promise.all([
        api.get("/student/me", { headers: getStudentHeaders() }),
        api.get("/student/registrations", { headers: getStudentHeaders() }),
      ]);
      setProfile(meRes.data);
      setRegistrations(Array.isArray(regRes.data) ? regRes.data : []);
    } catch (err) {
      // 401 signs out via the api.js interceptor. Return WITHOUT clearing loading (hence no
      // `finally`): empty registrations + loading=false paints "You have no registrations yet"
      // behind the redirect.
      if (err.response?.status === 401) {
        return;
      }
      // Everything else surfaces here — 403 included: it means "authenticated but denied", so the
      // server's reason is shown in place rather than swallowed into a blank dashboard.
      setError(
        err.response?.data?.message ||
          "Unable to load your dashboard. Please refresh and try again.",
      );
    }
    setLoading(false); // deliberately NOT a finally — the 401 above must leave loading set
  }, []);

  // NOTE: react-hooks/set-state-in-effect flags this (load setStates internally). Intended and
  // correct — fetch-on-mount into an external system, state lands in the async body.
  // A knowing lint error, deliberately not disabled.
  useEffect(() => {
    load();
  }, [load]);

  const handleLogout = async () => {
    await logoutStudent(); // expire the httpOnly cookie, then clear local state
    navigate("/student/login");
  };

  const startEditPhone = () => {
    setPhoneInput(profile?.phone || "");
    setPhoneError("");
    setEditingPhone(true);
  };

  const savePhone = async () => {
    if (!/^[0-9]{10}$/.test(phoneInput)) {
      setPhoneError("Phone number must be exactly 10 digits.");
      return;
    }
    setSavingPhone(true);
    setPhoneError("");
    try {
      const res = await api.put(
        "/student/me/phone",
        { phone: phoneInput },
        { headers: getStudentHeaders() },
      );
      setProfile(res.data);
      setEditingPhone(false);
    } catch (err) {
      setPhoneError(err.response?.data?.message || "Could not update phone number.");
    } finally {
      setSavingPhone(false);
    }
  };

  const downloadPdf = async (regId) => {
    setDownloadingId(regId);
    setDownloadError(null); // a retry shouldn't sit under the previous attempt's message
    try {
      const res = await api.get(`/student/registrations/${regId}/pdf`, {
        headers: getStudentHeaders(),
        responseType: "blob",
      });
      saveBlob(res.data, `backlog-registration-${profile?.rollNo || regId}.pdf`, "application/pdf");
    } catch (err) {
      // only 401 is handled globally (sign-out); 403 belongs here like any other denial
      if (err.response?.status !== 401) {
        // the server's reason is the actionable part (e.g. a 409 telling the student which detail
        // is missing and to contact the department office) — a generic alert threw it away
        setDownloadError({
          regId,
          message: await readBlobErrorMessage(
            err,
            "Could not download the form. Please try again.",
          ),
        });
      }
    } finally {
      setDownloadingId("");
    }
  };

  // branch code is the 2-letter code embedded in the USN: 1MS22CS001 -> CS
  const branchCode = profile?.rollNo ? profile.rollNo.slice(5, 7) : "";
  const branchLabel = profile?.branch
    ? `${profile.branch}${branchCode ? ` (${branchCode})` : ""}`
    : "";

  return (
    <div className="min-h-screen bg-surface-1 px-4 py-8 sm:px-6 lg:px-8">
      <div className="mx-auto w-full max-w-4xl">
        <BrandHeader className="mb-6">
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <Link
              to="/"
              className="inline-flex items-center gap-1 rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
            >
              <ArrowLeft size={15} /> Home
            </Link>
            <button
              type="button"
              onClick={handleLogout}
              className="inline-flex items-center gap-2 rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
            >
              <LogOut size={15} /> Log out
            </button>
          </div>
        </BrandHeader>

        {loading ? (
          <p className="inline-flex items-center gap-2 text-sm text-ink-muted">
            <LoaderCircle size={18} className="animate-spin" /> Loading your dashboard...
          </p>
        ) : error ? (
          <AlertBanner tone="error" role="alert">
            {error}
          </AlertBanner>
        ) : (
          <>
            {/* Profile */}
            <section
              className="mb-6 rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6"
            >
              <h1 className="mb-1 text-2xl font-semibold text-secondary-ink">
                {profile?.name || "Student"}
              </h1>
              <p className="mb-4 text-sm text-ink-muted">{profile?.rollNo}</p>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Email" value={profile?.email} />
                <Field label="Branch" value={branchLabel} />
                <Field
                  label="Current Semester"
                  value={profile?.currentSemester ? `Semester ${profile.currentSemester}` : ""}
                />

                {/* Phone — the only editable field */}
                <div className="flex flex-col gap-1.5 sm:col-span-2">
                  <span className="text-xs font-semibold uppercase tracking-[0.08em] text-ink-muted">
                    Phone
                  </span>
                  {editingPhone ? (
                    <div className="flex flex-wrap items-center gap-2">
                      <input
                        value={phoneInput}
                        onChange={(e) =>
                          setPhoneInput(e.target.value.replace(/\D/g, "").slice(0, 10))
                        }
                        inputMode="numeric"
                        maxLength={10}
                        placeholder="10 digit number"
                        className="w-44 rounded-xl border border-stroke bg-surface-1 px-3.5 py-2 text-sm text-ink outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
                        data-cy="phone-input"
                      />
                      <button
                        type="button"
                        onClick={savePhone}
                        disabled={savingPhone}
                        className="inline-flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-sm font-semibold text-white disabled:opacity-60"
                        data-cy="phone-save"
                      >
                        {savingPhone ? (
                          <LoaderCircle size={14} className="animate-spin" />
                        ) : (
                          <Check size={14} />
                        )}
                        Save
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditingPhone(false)}
                        className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-2 text-sm font-semibold text-ink"
                      >
                        <X size={14} /> Cancel
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-3">
                      <span
                        className={`inline-flex items-center gap-1.5 text-sm ${
                          profile?.phone ? "text-ink" : "text-ink-muted"
                        }`}
                        data-cy="phone-value"
                      >
                        <Phone size={14} /> {profile?.phone || "Not set"}
                      </span>
                      <button
                        type="button"
                        onClick={startEditPhone}
                        className="inline-flex items-center gap-1 text-sm font-semibold text-primary-ink hover:underline"
                        data-cy="phone-edit"
                      >
                        <Pencil size={13} /> {profile?.phone ? "Edit" : "Add phone"}
                      </button>
                    </div>
                  )}
                  {phoneError ? (
                    <p className="text-xs text-red-600">{phoneError}</p>
                  ) : !profile?.phone ? (
                    <p className="text-xs text-ink-muted">
                      Add your phone number before registering for backlog exams.
                    </p>
                  ) : null}
                </div>
              </div>

              <div className="mt-6 border-t border-stroke pt-5">
                <MagneticCta
                  onClick={() => navigate("/register")}
                  className="gap-2 rounded-xl"
                  data-cy="register-cta"
                >
                  Register for backlog subjects <ArrowRight size={16} />
                </MagneticCta>
              </div>
            </section>

            {/* Submissions */}
            <section
              className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-6"
            >
              <h2 className="mb-4 text-xl font-semibold text-ink">
                Your Submissions
              </h2>
              {registrations.length === 0 ? (
                <p className="rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm text-ink">
                  You have no registrations yet.
                </p>
              ) : (
                <ul className="flex flex-col gap-3">
                  {registrations.map((reg) => (
                    <li
                      key={reg.regId}
                      className="flex flex-col items-start justify-between gap-3 rounded-2xl border border-stroke bg-surface-muted p-4 sm:flex-row sm:items-start"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="mb-1 flex items-center gap-2">
                          <span
                            className={`inline-flex rounded-full border px-2.5 py-0.5 text-xs font-semibold ${statusBadgeClass(
                              reg.status,
                            )}`}
                          >
                            {reg.status}
                          </span>
                          {reg.examCycle ? (
                            <span className="text-xs text-ink-muted">{reg.examCycle}</span>
                          ) : null}
                        </div>
                        <p className="text-sm break-words text-ink">
                          {/* Bare on purpose: StudentController builds `subjects` from a stream
                              collect, so it is always a list. A guard here would turn a contract
                              break into a silent "No subjects" on a form that has some. */}
                          {reg.subjects.join(", ") || "No subjects"}
                        </p>
                        <p className="text-xs text-ink-muted">
                          {reg.registeredAt ? new Date(reg.registeredAt).toLocaleString() : ""}
                        </p>
                        {downloadError?.regId === reg.regId && (
                          <AlertBanner
                            tone="error"
                            role="alert"
                            data-cy="download-error"
                            className="mt-2"
                          >
                            {downloadError.message}
                          </AlertBanner>
                        )}
                      </div>
                      <button
                        type="button"
                        onClick={() => downloadPdf(reg.regId)}
                        disabled={downloadingId === reg.regId}
                        className="inline-flex shrink-0 items-center gap-2 self-stretch justify-center rounded-xl border border-stroke bg-surface-1 px-4 py-2 text-sm font-semibold text-secondary-ink transition-colors hover:border-primary hover:text-primary-ink disabled:opacity-60 sm:self-start sm:justify-start"
                        data-cy="download-pdf"
                      >
                        {downloadingId === reg.regId ? (
                          <LoaderCircle size={15} className="animate-spin" />
                        ) : (
                          <Download size={15} />
                        )}
                        Download form
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}

function Field({ label, value }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-semibold uppercase tracking-[0.08em] text-ink-muted">
        {label}
      </span>
      <span className="text-sm text-ink">{value || "—"}</span>
    </div>
  );
}

export default StudentDashboardPage;
