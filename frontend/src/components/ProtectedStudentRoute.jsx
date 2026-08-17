import { Navigate, useLocation } from "react-router-dom";
import { getStudentToken } from "../lib/api";
import { useSessionTimeout } from "../hooks/useSessionTimeout";
import SessionWarningBanner from "./SessionWarningBanner";

// Gates student-only routes: with no student token, redirect to login, preserving where they
// were headed so login can return there.
function ProtectedStudentRoute({ children }) {
  const location = useLocation();
  // sign out when the fixed session ends (no-ops without a token)
  const minutesLeft = useSessionTimeout("student");
  if (!getStudentToken()) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/student/login?redirect=${redirect}`} replace />;
  }
  return (
    <>
      <SessionWarningBanner minutesLeft={minutesLeft} />
      {children}
    </>
  );
}

export default ProtectedStudentRoute;
