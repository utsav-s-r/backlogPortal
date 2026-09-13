import { useState, useEffect, useRef } from "react";
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

// TEMPORARY iOS Chrome dead-button diagnosis, shown only with ?debug=1. Remove with the real fix.
function describeEl(el) {
  if (!el || !el.tagName) return "null";
  const tag = el.tagName.toLowerCase();
  const cy = el.getAttribute?.("data-cy");
  const text = (el.textContent || "").trim().slice(0, 14);
  return [tag, el.id && `#${el.id}`, cy && `[${cy}]`, text && `"${text}"`].filter(Boolean).join("");
}

function debugLine(line) {
  return `${new Date().toISOString().slice(17, 23)} ${line}`;
}

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
  const submitRef = useRef(null);

  const debug = searchParams.get("debug") === "1";
  const [debugLog, setDebugLog] = useState(() => [
    debugLine(`debug on, coarse=${window.matchMedia("(pointer: coarse)").matches}`),
  ]);
  const pushDebug = (line) => {
    if (debug) setDebugLog((prev) => [...prev.slice(-13), debugLine(line)]);
  };

  // Capture phase on document: records every touch/click that reaches the page, and what element
  // the browser thinks is under the finger, before any React handler runs.
  useEffect(() => {
    if (!debug) return undefined;
    const push = (line) => setDebugLog((prev) => [...prev.slice(-13), debugLine(line)]);
    const onTouch = (e) => {
      const t = e.changedTouches[0];
      const x = Math.round(t.clientX);
      const y = Math.round(t.clientY);
      push(`${e.type} ${x},${y} target=${describeEl(e.target)} atPoint=${describeEl(document.elementFromPoint(x, y))}`);
    };
    const onPointer = (e) => push(`${e.type} ${e.pointerType} target=${describeEl(e.target)}`);
    const onClick = (e) => push(`click target=${describeEl(e.target)}`);
    const onFocusIn = (e) => push(`focusin ${describeEl(e.target)}`);
    const listeners = [
      ["touchstart", onTouch],
      ["touchend", onTouch],
      ["pointerdown", onPointer],
      ["click", onClick],
      ["focusin", onFocusIn],
    ];
    listeners.forEach(([type, fn]) => document.addEventListener(type, fn, true));
    return () => listeners.forEach(([type, fn]) => document.removeEventListener(type, fn, true));
  }, [debug]);

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
    pushDebug("form submit fired");
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
                pushDebug(`dob change ${e.target.value}`);
              }}
              onFocus={() => pushDebug("dob focus")}
              // iOS Chrome: after its date picker closes, taps on Login / Back to home are
              // swallowed until focus lands on another element. Not onChange: iOS fires it on every
              // wheel movement, so the picker would close mid-pick. Only when focus is going
              // nowhere (relatedTarget null), so a tap on USN keeps its keyboard. Touch only.
              onBlur={(e) => {
                const coarse = window.matchMedia("(pointer: coarse)").matches;
                pushDebug(`dob blur related=${describeEl(e.relatedTarget)} coarse=${coarse}`);
                if (e.relatedTarget === null && coarse) {
                  submitRef.current?.focus();
                  pushDebug(`focused submit, active=${describeEl(document.activeElement)}`);
                }
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
            ref={submitRef}
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

        {debug ? (
          <pre
            className="mt-4 whitespace-pre-wrap break-all rounded-lg bg-surface-muted p-2 text-left text-[10px] leading-tight text-ink"
            data-cy="debug-log"
          >
            {debugLog.join("\n")}
          </pre>
        ) : null}
      </div>
    </PageLayout>
  );
}

export default StudentLoginPage;
