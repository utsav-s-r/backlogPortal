import { useEffect } from "react";
import { useNavigate } from "react-router-dom";

/**
 * Page-level role gate: a signed-in staff account on a page its role may not use goes to /admin.
 *
 * NOT a security control, and never the reason an endpoint may skip its @PreAuthorize. Every
 * /api/admin/** call is authorized server-side per request; `adminRole` lives in sessionStorage,
 * which the browser owns and anyone can edit. This exists so a wrong-role user lands somewhere
 * useful instead of on a page whose every request 403s.
 *
 * It answers ROLE only — never "is there a session". `ProtectedAdminRoute` already redirects to
 * /admin/login when `adminToken` is absent, and every /admin/* route is wrapped in it, so a page
 * body cannot run without one. The six `!adminToken` checks this replaced were unreachable.
 *
 * Unified three idioms (2026-08-31). Two pages sent a wrong-role user to `/admin/login`, where
 * AdminLoginPage sees a live token and bounces straight back to /admin — a double redirect that
 * mounted the login page and fired a wasted `GET /api/departments` on every such visit (observed
 * in the network log; the intermediate is too brief to catch by polling). /admin is the one
 * destination: it is where a staff account of any role can always go.
 *
 * `replace: true` so the forbidden page does not sit in history — Back from /admin would otherwise
 * return to it and bounce again, which is a trap rather than a navigation.
 *
 * @param {readonly string[]} allowedRoles a subset from lib/roles.js
 * @returns {boolean} whether to render; callers do `if (!allowed) return null;` so nothing paints
 *          (and no fetch fires) in the frame before the redirect commits.
 */
export function useRoleGuard(allowedRoles) {
  const navigate = useNavigate();
  const adminRole = sessionStorage.getItem("adminRole") || "";

  // The membership test lives INSIDE the effect and `allowed` is deliberately not a dependency:
  // a component-scope const computed from `.includes()` on a named array, feeding a hook dep array,
  // trips react-hooks' preserve-manual-memoization rule — a LINT error, not a runtime cost.
  // See lib/roles.js.
  useEffect(() => {
    if (!allowedRoles.includes(adminRole)) {
      navigate("/admin", { replace: true });
    }
  }, [allowedRoles, adminRole, navigate]);

  return allowedRoles.includes(adminRole);
}
