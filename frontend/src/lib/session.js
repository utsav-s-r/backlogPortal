// Session-expiry helpers. The JWT lives in an httpOnly cookie, so JS can't read its `exp`; login
// returns `expiresIn` (seconds) instead, and the derived `expiresAt` is persisted so the SPA can
// sign the user out on time. Sessions are a fixed 1h and are NOT renewable — there is no refresh
// endpoint, so this value only ever counts down. See useSessionTimeout.
import { clearAdminSession, clearStudentSession } from "./api";

// Per-audience wiring: the two flows keep separate cookies and login screens.
export const SESSION_SCOPES = {
  admin: {
    expiresKey: "adminExpiresAt",
    clear: clearAdminSession,
    loginPath: "/admin/login",
  },
  student: {
    expiresKey: "studentExpiresAt",
    clear: clearStudentSession,
    loginPath: "/student/login",
  },
};

// Persisted absolute expiry (ms since epoch) for a scope; null if absent or invalid.
export function sessionExpiryMs(scope) {
  const cfg = SESSION_SCOPES[scope];
  if (!cfg) return null;
  const raw = sessionStorage.getItem(cfg.expiresKey);
  const value = Number(raw);
  return raw && Number.isFinite(value) ? value : null;
}

// Store expiresAt from an `expiresIn` (seconds); called on login, the only issue point.
export function rememberExpiry(scope, expiresInSeconds) {
  const cfg = SESSION_SCOPES[scope];
  const secs = Number(expiresInSeconds);
  if (cfg && Number.isFinite(secs)) {
    sessionStorage.setItem(cfg.expiresKey, String(Date.now() + secs * 1000));
  }
}

/**
 * The signed-in user's own department row. Matched by ID: the name is display-only and editable, so
 * matching on it lets a rename silently un-pin everyone already signed in. The name fallback covers
 * a session whose storage predates `adminDepartmentId` — un-pinning mid-window would be worse.
 */
export function findOwnDepartment(departments, adminDepartment) {
  if (!Array.isArray(departments) || departments.length === 0) return null;
  const id = sessionStorage.getItem("adminDepartmentId");
  if (id) {
    const byId = departments.find((d) => String(d.id) === id);
    if (byId) return byId;
  }
  return adminDepartment
    ? departments.find((d) => d.deptName === adminDepartment) || null
    : null;
}
