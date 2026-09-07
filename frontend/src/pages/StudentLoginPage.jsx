import { useState, useEffect } from "react";
import { ArrowLeft, LogIn, LoaderCircle } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import PageLayout from "../components/layout/PageLayout";
import PrimaryCta from "../components/ui/PrimaryCta";
import api, { getStudentToken } from "../lib/api";
import { rememberExpiry } from "../lib/session";
import { safeRedirect } from "../lib/redirect";
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

  // Already signed in — skip the form for the dashboard. Same shape as the admin login: the
  // marker is a presence hint, and an expired cookie returns as ?expired=1 with the marker
  // already cleared by the 401 interceptor.
  useEffect(() => {
    if (!sessionExpired && getStudentToken()) {
      navigate("/student", { replace: true });
    }
  }, [sessionExpired, navigate]);

  const handleLogin = async () => {
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
      });
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
      }
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Invalid USN or date of birth.");
    } finally {
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

        <div className="space-y-4">
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
              onChange={(e) => setUsn(e.target.value.toUpperCase())}
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
              onChange={(e) => setDob(e.target.value)}
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
            onClick={handleLogin}
            className="mt-2 w-full"
            disabled={loading}
            data-cy="student-login-submit"
            aria-label="Student login"
          >
            {loading ? <LoaderCircle size={16} className="animate-spin" /> : <LogIn size={16} />} Login
          </PrimaryCta>
        </div>

        <div className="mt-6 text-center">
          <Link
            to="/"
            className={btn()}
          >
            <ArrowLeft size={14} /> Back to home
          </Link>
        </div>
      </div>
    </PageLayout>
  );
}

export default StudentLoginPage;
