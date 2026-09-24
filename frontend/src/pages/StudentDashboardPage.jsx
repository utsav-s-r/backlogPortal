import { useState, useEffect, useCallback } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Download,
  LoaderCircle,
  LogOut,
  Mail,
  Pencil,
  Phone,
  Check,
  X,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link, useNavigate } from "react-router-dom";
import PageLayout from "../components/layout/PageLayout";
import { btn } from "../lib/buttonClasses";
import PrimaryCta from "../components/ui/PrimaryCta";
import api, { logoutStudent } from "../lib/api";
import { saveBlob, readBlobErrorMessage } from "../lib/download";
import ReadOnlyField from "../components/ui/ReadOnlyField";
import HeaderPill from "../components/ui/HeaderPill";
import { FIELD_CONTROL, FIELD_LABEL } from "../lib/formClasses";
import { PHONE_ERROR, PHONE_RE, cleanPhoneInput } from "../lib/phone";
import {
  EMAIL_ERROR,
  EMAIL_MAX,
  EMAIL_MISMATCH,
  institutionalEmail,
  isValidEmail,
} from "../lib/email";

function statusBadgeClass(status) {
  if (status === "VERIFIED")
    return "bg-success-tint text-success";
  if (status === "REJECTED") return "bg-alert-tint text-alert";
  return "bg-surface-muted text-ink";
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

  const [editingEmail, setEditingEmail] = useState(false);
  const [emailInput, setEmailInput] = useState("");
  const [emailConfirm, setEmailConfirm] = useState("");
  const [savingEmail, setSavingEmail] = useState(false);
  const [emailError, setEmailError] = useState("");

  const [downloadingId, setDownloadingId] = useState("");
  // per-row, so the message sits next to the form it failed for rather than in a page-level banner
  const [downloadError, setDownloadError] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [meRes, regRes] = await Promise.all([
        api.get("/student/me"),
        api.get("/student/registrations"),
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
    if (!PHONE_RE.test(phoneInput)) {
      setPhoneError(PHONE_ERROR);
      return;
    }
    setSavingPhone(true);
    setPhoneError("");
    try {
      const res = await api.put("/student/me/phone", { phone: phoneInput });
      setProfile(res.data);
      setEditingPhone(false);
    } catch (err) {
      setPhoneError(err.response?.data?.message || "Could not update phone number.");
    } finally {
      setSavingPhone(false);
    }
  };

  const startEditEmail = () => {
    setEmailInput("");
    setEmailConfirm("");
    setEmailError("");
    setEditingEmail(true);
  };

  const fillCollegeEmail = () => {
    const college = institutionalEmail(profile?.rollNo);
    setEmailInput(college);
    setEmailConfirm(college);
    setEmailError("");
  };

  const saveEmail = async () => {
    const next = emailInput.trim();
    if (!isValidEmail(next)) {
      setEmailError(EMAIL_ERROR);
      return;
    }
    // typo guard: a mistyped address silently loses every future email, so it is typed twice
    if (next !== emailConfirm.trim()) {
      setEmailError(EMAIL_MISMATCH);
      return;
    }
    setSavingEmail(true);
    setEmailError("");
    try {
      const res = await api.put("/student/me/email", { email: next });
      setProfile(res.data);
      setEditingEmail(false);
    } catch (err) {
      setEmailError(err.response?.data?.message || "Could not update email.");
    } finally {
      setSavingEmail(false);
    }
  };

  const downloadPdf = async (regId) => {
    setDownloadingId(regId);
    setDownloadError(null); // a retry shouldn't sit under the previous attempt's message
    try {
      const res = await api.get(`/student/registrations/${regId}/pdf`, {
        responseType: "blob",
      });
      saveBlob(res.data, `backlog-registration-${profile?.rollNo || regId}.pdf`, "application/pdf");
    } catch (err) {
      // only 401 is handled globally (sign-out); 403 belongs here like any other denial
      if (err.response?.status !== 401) {
        // the server's reason is the actionable part (e.g. a 409 telling the student which detail
        // is missing and to contact the department office) — a generic alert throws it away
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
    <PageLayout
      containerClassName="max-w-4xl"
      actions={
        <>
          <HeaderPill as={Link} to="/">
            <ArrowLeft size={15} /> Home
          </HeaderPill>
          <HeaderPill onClick={handleLogout}>
            <LogOut size={15} /> Log out
          </HeaderPill>
        </>
      }
    >
      <div>
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
              className="mb-6 py-5 sm:py-6"
            >
              <h1 className="mb-1 text-2xl font-semibold text-secondary-ink">
                {profile?.name || "Student"}
              </h1>
              <p className="mb-4 text-sm text-ink-muted">{profile?.rollNo}</p>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <ReadOnlyField label="Branch" value={branchLabel} />
                <ReadOnlyField
                  label="Current Semester"
                  value={profile?.currentSemester ? `Semester ${profile.currentSemester}` : ""}
                />

                {/* Email — editable, same shape and reasons as Phone below */}
                <div className="flex flex-col gap-1.5 sm:col-span-2">
                  <span className={`${FIELD_LABEL} text-ink-muted`}>Email</span>
                  {editingEmail ? (
                    <div className="flex flex-col gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <input
                          value={emailInput}
                          onChange={(e) => setEmailInput(e.target.value)}
                          type="email"
                          maxLength={EMAIL_MAX}
                          placeholder="New email"
                          aria-label="New email"
                          className={`${FIELD_CONTROL} w-72 max-w-full`}
                          data-cy="email-input"
                        />
                        <input
                          value={emailConfirm}
                          onChange={(e) => setEmailConfirm(e.target.value)}
                          type="email"
                          maxLength={EMAIL_MAX}
                          placeholder="Type it again"
                          aria-label="Confirm new email"
                          className={`${FIELD_CONTROL} w-72 max-w-full`}
                          data-cy="email-confirm"
                        />
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          onClick={saveEmail}
                          disabled={savingEmail}
                          className={btn("accent")}
                          data-cy="email-save"
                        >
                          {savingEmail ? (
                            <LoaderCircle size={14} className="animate-spin" />
                          ) : (
                            <Check size={14} />
                          )}
                          Save
                        </button>
                        <button
                          type="button"
                          onClick={fillCollegeEmail}
                          className={btn("quiet")}
                          data-cy="email-use-college"
                        >
                          Use college email
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setEditingEmail(false);
                            setEmailError(""); // else a stale "don't match" sits under the saved value
                          }}
                          className={btn()}
                        >
                          <X size={14} /> Cancel
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex flex-wrap items-center gap-3">
                      <span
                        className="inline-flex min-w-0 items-center gap-1.5 break-all text-sm font-medium text-ink"
                        data-cy="email-value"
                      >
                        <Mail size={14} className="shrink-0" /> {profile?.email || "Not set"}
                      </span>
                      <button
                        type="button"
                        onClick={startEditEmail}
                        className={btn("neutral", "sm")}
                        data-cy="email-edit"
                      >
                        <Pencil size={13} /> Edit
                      </button>
                    </div>
                  )}
                  {emailError && (
                    <p className="text-xs text-alert" role="alert" data-cy="email-error">
                      {emailError}
                    </p>
                  )}
                </div>

                {/* Phone */}
                <div className="flex flex-col gap-1.5 sm:col-span-2">
                  {/* Not <Field>: this holds an input plus two buttons when editing, the
                      multi-interactive case Field must not wrap. Shares FIELD_LABEL so it stays
                      level with the ReadOnlyFields beside it in this grid. */}
                  <span className={`${FIELD_LABEL} text-ink-muted`}>Phone</span>
                  {editingPhone ? (
                    <div className="flex flex-wrap items-center gap-2">
                      <input
                        value={phoneInput}
                        onChange={(e) => setPhoneInput(cleanPhoneInput(e.target.value))}
                        type="tel"
                        inputMode="numeric"
                        maxLength={10}
                        placeholder="10 digit number"
                        className={`${FIELD_CONTROL} w-44`}
                        data-cy="phone-input"
                      />
                      <button
                        type="button"
                        onClick={savePhone}
                        disabled={savingPhone}
                        className={btn("accent")}
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
                        onClick={() => {
                          setEditingPhone(false);
                          setPhoneError("");
                        }}
                        className={btn()}
                      >
                        <X size={14} /> Cancel
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-3">
                      <span
                        className={`inline-flex items-center gap-1.5 text-sm font-medium ${
                          profile?.phone ? "text-ink" : "text-ink-muted"
                        }`}
                        data-cy="phone-value"
                      >
                        <Phone size={14} /> {profile?.phone || "Not set"}
                      </span>
                      <button
                        type="button"
                        onClick={startEditPhone}
                        className={btn("neutral", "sm")}
                        data-cy="phone-edit"
                      >
                        <Pencil size={13} /> {profile?.phone ? "Edit" : "Add phone"}
                      </button>
                    </div>
                  )}
                  {phoneError ? (
                    <p className="text-xs text-alert">{phoneError}</p>
                  ) : !profile?.phone ? (
                    <p className="text-xs text-ink-muted">
                      Add your phone number before registering for backlog exams.
                    </p>
                  ) : null}
                </div>
              </div>

              <div className="mt-6 border-t border-stroke pt-5">
                <PrimaryCta
                  onClick={() => navigate("/register")}
                  className="gap-2"
                  data-cy="register-cta"
                >
                  Register for backlog subjects <ArrowRight size={16} />
                </PrimaryCta>
              </div>
            </section>

            {/* Submissions */}
            <section
              className="py-5 sm:py-6"
            >
              <h2 className="mb-4 text-xl font-semibold text-ink">
                Your Submissions
              </h2>
              {registrations.length === 0 ? (
                <p className="rounded-lg bg-surface-muted px-4 py-3 text-sm text-ink">
                  You have no registrations yet.
                </p>
              ) : (
                <ul className="flex flex-col gap-3">
                  {registrations.map((reg) => (
                    <li
                      key={reg.regId}
                      className="flex flex-col items-start justify-between gap-3 rounded-lg bg-surface-muted p-4 sm:flex-row sm:items-start"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="mb-1 flex items-center gap-2">
                          <span
                            className={`inline-flex rounded-lg px-2.5 py-0.5 text-xs font-semibold ${statusBadgeClass(
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
                        className={`${btn()} shrink-0 self-stretch sm:self-start`}
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
    </PageLayout>
  );
}

export default StudentDashboardPage;
