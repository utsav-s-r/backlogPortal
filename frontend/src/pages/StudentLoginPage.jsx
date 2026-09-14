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
  // New press aborts the old one, so only the latest reply lands. Don't disable Login while
  // loading: it blocks the retry.
  const nextSignal = useAbortableRequest();
  // iOS Chrome fix, keep: on a non-scrollable page, Login/Back die after the date picker closes.
  // 200px extra height fixes it; scrolling from code does not.
  const isTouch = window.matchMedia("(pointer: coarse)").matches;

  // Signed in → dashboard. On ?expired=1 the 401 interceptor already cleared the marker.
  useEffect(() => {
    if (!sessionExpired && getStudentToken()) {
      navigate("/student", { replace: true });
    }
  }, [sessionExpired, navigate]);

  // <form> submit: iOS "Go" key works even when the keyboard covers Login.
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
        // JWT is an httpOnly cookie; store only a marker, UI state and the expiry.
        sessionStorage.setItem("studentToken", "cookie");
        sessionStorage.setItem("studentRollNo", res.data.rollNo || usn);
        sessionStorage.setItem("studentName", res.data.name || "");
        rememberExpiry("student", res.data.expiresIn);
        // ?redirect= is attacker-controlled: always through safeRedirect.
        navigate(safeRedirect(searchParams.get("redirect"), "/student"));
      } else {
        setError("Login failed. Please try again.");
        setLoading(false);
      }
    } catch (apiError) {
      // No `finally`: it would clear the newer attempt's spinner. Success leaves it (navigating).
      if (apiError.code === "ERR_CANCELED") return; // superseded
      setError(apiError.response?.data?.message || "Invalid USN or date of birth.");
      setLoading(false);
    }
  };

  return (
    <PageLayout
      containerClassName="max-w-md"
      fullHeightClassName={isTouch ? "min-h-[calc(100dvh_+_200px)]" : "min-h-screen"}
    >
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
              // Clear stale error on edit.
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
