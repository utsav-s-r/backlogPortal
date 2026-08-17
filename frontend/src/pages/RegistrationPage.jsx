import { useState, useEffect, useMemo } from "react";
import {
  ArrowLeft,
  CalendarX,
  CheckCircle2,
  Download,
  LoaderCircle,
  Phone,
  TriangleAlert,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link, useNavigate } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";
import api, { getStudentHeaders } from "../lib/api";
import { formatAcademicYear } from "../lib/academicYear";
import { saveBlob, readBlobErrorMessage } from "../lib/download";

function RegistrationPage() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null); // null = loading
  const [profileError, setProfileError] = useState("");
  const [subjects, setSubjects] = useState([]);
  const [selectedSubjects, setSelectedSubjects] = useState([]);
  const [loadingSubjects, setLoadingSubjects] = useState(false);
  const [subjectsError, setSubjectsError] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [regId, setRegId] = useState("");
  const [submitError, setSubmitError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState("");
  const [searchSemester, setSearchSemester] = useState("");
  // academic year the chosen semester resolved to, server-derived and shown read-only
  const [resolvedAcademicYear, setResolvedAcademicYear] = useState(null);
  // null = still checking; otherwise { open, cycleName?, examMonthYear? }
  const [regStatus, setRegStatus] = useState(null);

  // identity comes from the authenticated account, never a form
  useEffect(() => {
    api
      .get("/student/me", { headers: getStudentHeaders() })
      .then((res) => setProfile(res.data))
      .catch((err) => {
        // 401 signs out via the global interceptor; 403 and everything else show in place
        if (err.response?.status !== 401) {
          setProfileError(
            err.response?.data?.message ||
              "Unable to load your profile. Please try again.",
          );
          setProfile({});
        }
      });
  }, []);

  useEffect(() => {
    api
      .get("/registration-status")
      .then((res) => setRegStatus(res.data))
      // fail closed: without a confirmed open cycle, don't start a submission the backend
      // would reject anyway
      .catch(() => setRegStatus({ open: false }));
  }, []);

  // backlog semesters registerable, from the server-derived current semester
  const eligibleSemesters = useMemo(() => {
    const fromProfile = profile?.eligibleSemesters;
    return Array.isArray(fromProfile) ? fromProfile : [];
  }, [profile]);

  // The server resolves subjects from the student's progression — the academic year is not a
  // client choice, so only the semester is sent.
  // NOTE: react-hooks/set-state-in-effect flags the resets below. Intended and correct — this
  // fetches subjects for the chosen semester, clearing previous results before bailing when none
  // is selected. Both clear and fetch synchronize UI with an external system, the case the rule
  // carves out. A knowing lint error, deliberately not disabled.
  useEffect(() => {
    if (!searchSemester) {
      setSubjects([]);
      setResolvedAcademicYear(null);
      return;
    }

    let ignoreResponse = false;
    // abort a superseded fetch (semester re-picked before the reply landed) so it stops using a
    // backend connection — the flag alone only hid the response
    const controller = new AbortController();
    setLoadingSubjects(true);
    setSubjectsError("");

    api
      .get("/student/subjects", {
        headers: getStudentHeaders(),
        params: { semester: searchSemester },
        signal: controller.signal,
      })
      .then((res) => {
        if (ignoreResponse) return;
        setSubjects(Array.isArray(res.data?.subjects) ? res.data.subjects : []);
        setResolvedAcademicYear(res.data?.academicYear ?? null);
      })
      .catch((err) => {
        if (ignoreResponse || err.code === "ERR_CANCELED") return;
        setSubjects([]);
        setResolvedAcademicYear(null);
        // surface the server's explanation, e.g. a missing progression record
        setSubjectsError(
          err.response?.data?.message || "Unable to load subjects. Please try again.",
        );
        console.error("Failed to fetch subjects", err);
      })
      .finally(() => {
        if (ignoreResponse) return;
        setLoadingSubjects(false);
      });

    return () => {
      ignoreResponse = true;
      controller.abort();
    };
  }, [searchSemester]);

  const handleSubjectToggle = (subject) => {
    setSelectedSubjects((prev) =>
      prev.some((s) => s.id === subject.id) ? prev.filter((s) => s.id !== subject.id) : [...prev, subject],
    );
  };

  const handleSubmit = async () => {
    if (selectedSubjects.length === 0) {
      setSubmitError("Please select at least one subject.");
      return;
    }
    if (!searchSemester) {
      setSubmitError("Please select the semester you are registering for.");
      return;
    }

    setSubmitting(true);
    setSubmitError("");
    try {
      const res = await api.post(
        "/register",
        {
          subjectIds: selectedSubjects.map((s) => s.id),
        },
        { headers: getStudentHeaders() },
      );
      setRegId(res.data.regId);
      setSubmitted(true);
    } catch (error) {
      setSubmitError(
        error.response?.data?.message || "Submission failed. Please review details and try again.",
      );
      console.error(error);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDownloadPdf = async () => {
    setDownloading(true);
    setDownloadError("");
    try {
      const res = await api.get(`/student/registrations/${regId}/pdf`, {
        headers: getStudentHeaders(),
        responseType: "blob",
      });
      saveBlob(res.data, `backlog-registration-${profile?.rollNo || regId}.pdf`, "application/pdf");
    } catch (err) {
      // surface the server's reason — a 409 here names the missing detail and says to contact the
      // department office, which is the one thing the student can act on
      setDownloadError(
        await readBlobErrorMessage(
          err,
          "Could not download the form. Please try again from your dashboard.",
        ),
      );
    } finally {
      setDownloading(false);
    }
  };

  // ---- gated render states ----
  if (regStatus === null || profile === null) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-1 px-4 text-ink-muted">
        <p className="inline-flex items-center gap-2 text-sm">
          <LoaderCircle size={18} className="animate-spin" /> Loading...
        </p>
      </div>
    );
  }

  // The profile never loaded (e.g. a 403 denial). Without this the empty profile falls through to
  // the "add your phone number" gate below and the student is told to fix the wrong thing.
  if (profileError && !profile.rollNo) {
    return (
      <CenteredCard icon={<TriangleAlert size={24} />} title="Cannot load your profile">
        <p className="mb-6 text-ink" data-cy="registration-profile-error">
          {profileError}
        </p>
      </CenteredCard>
    );
  }

  // `!== true`, not `=== false`: a 200 whose body is missing `open` must land here, not fall
  // through to an open form the server would then reject.
  if (regStatus.open !== true) {
    return (
      <CenteredCard icon={<CalendarX size={24} />} title="No Open Registrations">
        {/* Also the fail-closed landing spot for a failed status check, so the copy names no
            cause (an exam cycle may well be open) and the refresh doubles as the retry. */}
        <p className="mb-6 text-ink">
          There are no backlog registrations open right now. If your department has announced a
          registration window, refresh the page.
        </p>
        <BackLink to="/student" label="Back to Dashboard" />
      </CenteredCard>
    );
  }

  // phone is required and settable only in the dashboard
  if (!profile.phone) {
    return (
      <CenteredCard icon={<Phone size={24} />} title="Add your phone number">
        <p className="mb-6 text-ink">
          You need a phone number on your profile before you can register for backlog exams. Please
          add it in your dashboard and come back.
        </p>
        <MagneticCta onClick={() => navigate("/student")} className="gap-2 rounded-xl">
          Go to Dashboard
        </MagneticCta>
      </CenteredCard>
    );
  }

  if (submitted) {
    return (
      <CenteredCard
        eyebrow="Submission Complete"
        title="Registration Submitted"
      >
        {/* regId is an internal UUID — nothing quotes it (no track-by-id, no PDF field, no admin
            column; staff look up by USN), so showing it only competes with the actual next step. */}
        <p className="mb-6 text-ink">
          Please download your form, print it, and get it signed by your Proctor and HOD.
        </p>
        {downloadError && (
          <AlertBanner
            tone="error"
            role="alert"
            data-cy="download-error"
            className="mb-4"
          >
            {downloadError}
          </AlertBanner>
        )}
        <div className="flex flex-wrap gap-3">
          <MagneticCta onClick={handleDownloadPdf} disabled={downloading} className="gap-2">
            {downloading ? <LoaderCircle size={16} className="animate-spin" /> : <Download size={16} />}
            Download PDF
          </MagneticCta>
          <Link
            to="/student"
            className="inline-flex items-center justify-center rounded-full border border-stroke bg-surface-1 px-5 py-3 text-sm font-semibold text-secondary-ink transition-colors hover:border-primary hover:text-primary-ink"
          >
            <ArrowLeft size={16} /> Back to Dashboard
          </Link>
        </div>
      </CenteredCard>
    );
  }

  // ---- main subject-selection form ----
  return (
    <div className="min-h-screen bg-surface-1 text-ink">
      <div className="mx-auto w-full max-w-6xl px-4 py-8 sm:px-6 lg:px-8">
        <BrandHeader className="mb-6">
          <Link
            to="/student"
            aria-label="Back to dashboard"
            className="inline-flex items-center rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
          >
            <ArrowLeft size={15} className="mr-1" /> Dashboard
          </Link>
        </BrandHeader>

        <div
          className="rounded-3xl border border-stroke bg-surface-1 p-5 shadow-soft sm:p-8"
        >
          {/* locked identity summary */}
          <section className="mb-6">
            <h2 className="mb-3 text-xl font-semibold text-ink">Registering as</h2>
            <div className="grid grid-cols-1 gap-3 rounded-2xl border border-stroke bg-surface-muted p-4 sm:grid-cols-2">
              <LockedField label="Name" value={profile.name} />
              <LockedField label="USN" value={profile.rollNo} />
              <LockedField label="Email" value={profile.email} />
              <LockedField label="Branch" value={profile.branch} />
              <LockedField
                label="Current Semester"
                value={profile.currentSemester ? `Semester ${profile.currentSemester}` : ""}
              />
              <LockedField label="Phone" value={profile.phone} />
            </div>
            {profileError ? (
              <p className="mt-2 text-xs text-red-600">{profileError}</p>
            ) : null}
          </section>

          <section className="mb-6 border-t border-stroke pt-6">
            <h2 className="mb-4 text-xl font-semibold text-ink">Selected Subjects</h2>
            {selectedSubjects.length === 0 ? (
              <p className="mb-6 rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
                No subjects selected yet. Please search and add subjects below.
              </p>
            ) : (
              <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2">
                {selectedSubjects.map((subject) => (
                  <div
                    key={`sel-${subject.id}`}
                    className="flex items-center justify-between rounded-xl border border-primary/30 bg-primary-tint p-3 shadow-sm"
                  >
                    {/* min-w-0: flex items default to min-width:auto, so without it the 4-fact meta
                        line overflowed the card and squeezed Remove on mobile. */}
                    <div className="min-w-0">
                      <p className="break-words text-sm font-semibold text-ink sm:text-base">
                        {subject.subjectName}
                      </p>
                      {/* nowrap per fact: only the • separators are break points, so "Sem 1" and
                          "4 credits" never split across lines. */}
                      <p className="break-words text-xs text-ink sm:text-sm">
                        <span className="font-mono">{subject.courseCode}</span> •{" "}
                        {subject.department?.deptName} •{" "}
                        <span className="whitespace-nowrap">
                          Sem {subject.semester || searchSemester}
                        </span>{" "}
                        •{" "}
                        <span className="whitespace-nowrap">
                          {subject.credits} {subject.credits === 1 ? "credit" : "credits"}
                        </span>
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => handleSubjectToggle(subject)}
                      className="ml-3 shrink-0 rounded-md border border-stroke bg-surface-1 px-2 py-1 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50"
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            )}

            {selectedSubjects.length > 0 && (
              <div className="mb-6 flex items-center justify-between rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
                <span className="font-semibold text-ink">
                  {selectedSubjects.length}{" "}
                  {selectedSubjects.length === 1 ? "subject" : "subjects"} selected
                </span>
                <span className="font-semibold text-ink">
                  Total credits:{" "}
                  {selectedSubjects.reduce((sum, s) => sum + (s.credits || 0), 0)}
                </span>
              </div>
            )}

            <h2 className="mb-4 text-xl font-semibold text-ink">Search Backlog Subjects</h2>
            <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-1.5">
                <label
                  htmlFor="searchSemester"
                  className="text-xs font-semibold uppercase tracking-[0.08em] text-ink"
                >
                  Semester
                </label>
                <select
                  id="searchSemester"
                  className="rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed disabled:opacity-60"
                  value={searchSemester}
                  onChange={(e) => setSearchSemester(e.target.value)}
                  data-cy="reg-semester"
                  disabled={eligibleSemesters.length === 0}
                >
                  <option value="">Select semester</option>
                  {eligibleSemesters.map((sem) => (
                    <option key={sem} value={sem}>
                      Semester {sem}
                    </option>
                  ))}
                </select>
                {eligibleSemesters.length === 0 ? (
                  <p className="mt-1 text-xs text-red-600">
                    Your current semester isn't set up yet. Please contact the department office.
                  </p>
                ) : null}
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-semibold uppercase tracking-[0.08em] text-ink">
                  Academic Year
                </span>
                <div className="flex h-[42px] items-center rounded-xl border border-stroke bg-surface-muted px-3.5 text-sm text-ink">
                  {resolvedAcademicYear
                    ? formatAcademicYear(resolvedAcademicYear)
                    : "Set automatically from your record"}
                </div>
                <p className="text-xs text-ink-muted">
                  Resolved from the year you studied this semester.
                </p>
              </div>
            </div>

            {loadingSubjects ? (
              <p className="inline-flex items-center gap-2 rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
                <LoaderCircle size={16} className="animate-spin" /> Loading subjects...
              </p>
            ) : subjectsError ? (
              <AlertBanner tone="error">{subjectsError}</AlertBanner>
            ) : subjects.length === 0 ? (
              <p className="rounded-xl border border-stroke bg-surface-muted px-4 py-3 text-sm">
                {searchSemester
                  ? "No subjects found for the selected semester."
                  : "Select a semester to find subjects."}
              </p>
            ) : (
              <div className="grid grid-cols-1 gap-2">
                {subjects.map((subject) => {
                  const isSelected = selectedSubjects.some((s) => s.id === subject.id);
                  return (
                    <div
                      key={subject.id}
                      className={`rounded-xl border p-3 transition-transform duration-200 motion-safe:hover:translate-y-[-2px] ${
                        isSelected
                          ? "border-primary/45 bg-primary-tint opacity-60"
                          : "border-stroke bg-surface-muted"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          id={`subject-${subject.id}`}
                          checked={isSelected}
                          onChange={() => handleSubjectToggle(subject)}
                          className="h-4 w-4 accent-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
                        />
                        {/* Stacks under sm; shrink-0/right-align/truncate are sm+ only. Horizontal,
                            the meta's shrink-0 claimed the long deptName's width and the name, the
                            only shrinkable child, truncated to ~2 chars at 375px. */}
                        <label
                          htmlFor={`subject-${subject.id}`}
                          className="flex min-w-0 flex-1 cursor-pointer flex-col items-start gap-1 text-sm text-ink sm:flex-row sm:items-center sm:justify-between sm:gap-3 sm:text-base"
                        >
                          <span className="flex min-w-0 max-w-full flex-col">
                            <span className="break-words sm:truncate">{subject.subjectName}</span>
                            <span className="font-mono text-xs text-ink-muted">
                              {subject.courseCode}
                            </span>
                          </span>
                          {/* flex-wrap + nowrap credits: breaks land between the two facts, never
                              inside "N credits". sm:block restores the stacked right-aligned pair. */}
                          <span className="flex min-w-0 max-w-full flex-wrap items-baseline gap-x-1.5 text-left text-xs text-ink sm:block sm:shrink-0 sm:text-right sm:text-sm">
                            <span className="break-words">{subject.department?.deptName}</span>
                            <span className="whitespace-nowrap text-ink-muted sm:block">
                              <span className="sm:hidden">· </span>
                              {subject.credits} {subject.credits === 1 ? "credit" : "credits"}
                            </span>
                          </span>
                        </label>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          {submitError ? (
            <AlertBanner
              tone="error"
              role="alert"
              aria-live="polite"
              className="mb-4"
            >
              {submitError}
            </AlertBanner>
          ) : null}

          <MagneticCta
            type="button"
            onClick={handleSubmit}
            disabled={selectedSubjects.length === 0 || submitting}
            className="w-full gap-2 rounded-xl disabled:cursor-not-allowed disabled:opacity-60"
            data-cy="reg-submit"
            aria-label="Submit registration"
          >
            {submitting ? (
              <>
                <LoaderCircle size={16} className="animate-spin" /> Submitting...
              </>
            ) : (
              <>
                <CheckCircle2 size={16} /> Submit Registration
              </>
            )}
          </MagneticCta>
        </div>
      </div>
    </div>
  );
}

function LockedField({ label, value }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-semibold uppercase tracking-[0.08em] text-ink-muted">
        {label}
      </span>
      <span className="text-sm font-medium text-ink">{value || "—"}</span>
    </div>
  );
}

function CenteredCard({ icon, eyebrow, title, children }) {
  return (
    <div className="min-h-screen bg-surface-1 px-4 py-10 sm:px-6 lg:px-8">
      <div className="mx-auto mb-6 w-full max-w-2xl">
        <BrandHeader>
          <Link
            to="/student"
            aria-label="Back to dashboard"
            className="inline-flex items-center rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
          >
            <ArrowLeft size={15} className="mr-1" /> Dashboard
          </Link>
        </BrandHeader>
      </div>
      <div
        className="mx-auto w-full max-w-2xl rounded-3xl border border-stroke bg-surface-1 p-6 shadow-soft sm:p-8"
      >
        {eyebrow ? (
          <p className="mb-2 inline-flex rounded-full border border-primary/30 bg-surface-muted px-3 py-1 text-xs font-semibold uppercase tracking-[0.12em] text-primary-ink">
            {eyebrow}
          </p>
        ) : null}
        {icon ? (
          <span className="mb-4 inline-flex h-12 w-12 items-center justify-center rounded-full bg-surface-muted text-primary-ink">
            {icon}
          </span>
        ) : null}
        <h2 className="mb-3 text-3xl font-semibold text-secondary-ink">{title}</h2>
        {children}
      </div>
    </div>
  );
}

function BackLink({ to, label }) {
  return (
    <Link
      to={to}
      className="inline-flex items-center justify-center gap-2 rounded-full border border-stroke bg-surface-1 px-5 py-3 text-sm font-semibold text-secondary-ink transition-colors hover:border-primary hover:text-primary-ink"
    >
      <ArrowLeft size={16} /> {label}
    </Link>
  );
}

export default RegistrationPage;
