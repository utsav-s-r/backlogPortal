import { Navigate, useLocation } from "react-router-dom";
import { getAdminToken } from "../lib/api";
import { useSessionTimeout } from "../hooks/useSessionTimeout";
import SessionWarningBanner from "./SessionWarningBanner";

// Gates admin-only routes: with no admin token, redirect to login, preserving the intended
// destination so login can return there.
//
// NOT a security control, despite the name. `adminToken` is a presence marker in sessionStorage,
// which the browser owns — anyone can set it and render this route (every Cypress spec does, with
// the literal "admin-jwt-token"). An allowed-role set would add nothing: per-page `adminRole`
// checks read the same forgeable storage. Harmless only because the real credential is the httpOnly
// JWT cookie, unforgeable by JS, so a forged entry renders a shell whose every request still
// 401/403s. This gate's job is UX: redirect, keep the destination, run the expiry banner.
// Enforcement is server-side ONLY, per endpoint — never let this route be why an endpoint skips its
// @PreAuthorize, the day that happens the console trick becomes privilege escalation.
function ProtectedAdminRoute({ children }) {
  const location = useLocation();
  // sign out when the fixed session ends; runs unconditionally, no-ops without a token
  const minutesLeft = useSessionTimeout("admin");
  if (!getAdminToken()) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/admin/login?redirect=${redirect}`} replace />;
  }
  return (
    <>
      <SessionWarningBanner minutesLeft={minutesLeft} />
      {children}
    </>
  );
}

export default ProtectedAdminRoute;
