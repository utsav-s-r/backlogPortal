import { useState, useEffect } from "react";
import { ArrowLeft, LogIn, LoaderCircle } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import PageLayout from "../components/layout/PageLayout";
import PrimaryCta from "../components/ui/PrimaryCta";
import api, { getStudentToken } from "../lib/api";
import { rememberExpiry } from "../lib/session";
import { safeRedirect } from "../lib/redirect";
import { useAbortableRequest } from "../hooks/useAbortableRequest";
import AlertBanner from "../components/AlertBanner";
import { FIELD_INPUT, FIELD_LABEL } from "../lib/formClasses";
import { btn } from "../lib/buttonClasses";

const USN_PATTERN = /^1MS\d{2}[A-Z]{2}\d{3}$/;

function StudentLoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionExpired = searchParams.get("expired") === "1";
  const [usn, setUsn] = useState("");
  const [dob, setDob] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  // Pressing Login again ABORTS the attempt still running, so the reply that lands is always the
  // one for the credentials on screen. The button is deliberately NOT disabled while loading:
  // disabling it is what swallowed the retry, leaving a slow attempt's failure to report itself
  // over credentials the student had already corrected.
  const nextSignal = useAbortableRequest();

  // Already signed in — skip the form for the dashboard. Same shape as the admin login: the
  // marker is a presence hint, and an expired cookie returns as ?expired=1 with the marker
  // already cleared by the 401 interceptor.
  useEffect(() => {
    if (!sessionExpired && getStudentToken()) {
      navigate("/student", { replace: true });
    }
  }, [sessionExpired, navigate]);

  // Takes the submit event: the fields are in a <form>, so iOS offers a "Go" key and submitting
  // never depends on tapping a button the software keyboard is covering.
  const handleLogin = async (e) => {
    e?.preventDefault();
    if (!usn || !dob) {
      setError("USN and date of birth are required.");
      return;
    }
    if (!USN_PATTERN.test(usn)) {
      setError("USN must be in the format 1MS22CS001.");
      return;
    }

    setLoading(true);
    setError("");
    try {
      const res = await api.post("/student/auth/login", {
        rollNo: usn,
        dateOfBirth: dob, // native date input gives ISO yyyy-MM-dd
      }, { signal: nextSignal() });
      if (res.data.rollNo || res.data.name) {
        // the server set the JWT in an httpOnly cookie; store only a presence marker, UI state,
        // and the sign-out deadline
        sessionStorage.setItem("studentToken", "cookie");
        sessionStorage.setItem("studentRollNo", res.data.rollNo || usn);
        sessionStorage.setItem("studentName", res.data.name || "");
        rememberExpiry("student", res.data.expiresIn);
        // The dashboard is home base (profile, status, past registrations) and stays the default.
        // But when ProtectedStudentRoute bounced the student off an explicit deep link it encodes
        // that destination, and dropping it read as a broken link. Honour it — validated, never
        // raw: the param is attacker-controllable, and only /register and /student are reachable
        // anyway (RegistrationPage loads its own data, so it is a complete entry point).
        navigate(safeRedirect(searchParams.get("redirect"), "/student"));
      } else {
        setError("Login failed. Please try again.");
        setLoading(false);
      }
    } catch (apiError) {
      // No `finally`: it would also run on the early return below, clearing the spinner for the
      // NEWER attempt that superseded this one. Cleared on each terminal path instead — the
      // success path deliberately leaves it set, since the page is navigating away.
      if (apiError.code === "ERR_CANCELED") return; // superseded by a newer press
      setError(apiError.response?.data?.message || "Invalid USN or date of birth.");
      setLoading(false);
    }
  };

  return (
    <PageLayout containerClassName="max-w-md">
      <div className="py-6 sm:py-8">
        <div className="mb-6 text-left">
          <h1 className="text-3xl font-semibold text-secondary-ink">Sign in</h1>
          <p className="mt-2 text-sm text-ink">
            Log in with your USN and date of birth to register for backlog exams and download your forms.
          </p>
        </div>

        {sessionExpired ? (
          <AlertBanner
            tone="warning"
            role="status"
            data-cy="session-expired"
            className="mb-4"
          >
            Your session expired. Please sign in again.
          </AlertBanner>
        ) : null}

        <form onSubmit={handleLogin} className="space-y-4">
          <div>
            <label
              htmlFor="student-usn"
              className={`mb-1.5 block text-left ${FIELD_LABEL} text-ink`}
            >
              USN
            </label>
            <input
              id="student-usn"
              placeholder="e.g. 1MS22CS001"
              value={usn}
              // Editing clears the banner: otherwise a previous attempt's error survives the
              // correction and sits over credentials that are now right.
              onChange={(e) => {
                setUsn(e.target.value.toUpperCase());
                setError("");
              }}
              maxLength={10}
              className={FIELD_INPUT}
              data-cy="student-usn"
            />
          </div>

          <div>
            <label
              htmlFor="student-dob"
              className={`mb-1.5 block text-left ${FIELD_LABEL} text-ink`}
            >
              Date of Birth
            </label>
            <input
              id="student-dob"
              type="date"
              value={dob}
              onChange={(e) => {
                setDob(e.target.value);
                setError("");
              }}
              className={FIELD_INPUT}
              data-cy="student-dob"
            />
          </div>

          {error ? (
            <AlertBanner tone="error" role="alert" aria-live="polite">
              {error}
            </AlertBanner>
          ) : null}

          <PrimaryCta
            type="submit"
            className="mt-2 w-full"
            data-cy="student-login-submit"
            aria-label="Student login"
          >
            {loading ? <LoaderCircle size={16} className="animate-spin" /> : <LogIn size={16} />} Login
          </PrimaryCta>
        </form>

        <div className="mt-6 text-center">
          <Link
            to="/"
            className={`${btn()} w-full`}
          >
            <ArrowLeft size={14} /> Back to home
          </Link>
        </div>
      </div>
    </PageLayout>
  );
}

export default StudentLoginPage;
