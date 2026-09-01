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
 * body cannot run without one; a `!adminToken` check in a page is unreachable code.
 *
 * /admin is the ONLY correct destination — it is where a staff account of any role can always go.
 * Sending a wrong-role user to /admin/login instead makes AdminLoginPage see the live token and
 * bounce straight back, a double redirect that mounts the login page and fires a wasted
 * `GET /api/departments` on every such visit.
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
