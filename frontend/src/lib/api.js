import axios from "axios";

// The JWT travels in an httpOnly cookie the browser attaches automatically, never readable by JS
// so it can't be stolen via XSS. withCredentials sends it; the xsrf* options make axios echo the
// readable XSRF-TOKEN cookie as X-XSRF-TOKEN, so Spring's CSRF check passes on mutations.
const api = axios.create({
  baseURL: "/api",
  timeout: 15000,
  withCredentials: true,
  xsrfCookieName: "XSRF-TOKEN",
  xsrfHeaderName: "X-XSRF-TOKEN",
});

// NOTE: these sessionStorage values are NOT the credential — the real token is in the httpOnly
// cookie. "adminToken"/"studentToken" hold only a presence marker ("cookie") so the existing
// "session present?" checks and route guards keep working; the rest is UI state (role/name) and
// the sign-out deadline (expiresAt).
export function getAdminToken() {
  return sessionStorage.getItem("adminToken"); // presence marker, not the JWT
}

export function getStudentToken() {
  return sessionStorage.getItem("studentToken"); // presence marker, not the JWT
}

// Cookie-based auth needs no Authorization header. Kept as no-ops so the many
// `{ headers: getAdminHeaders() }` call sites don't all need editing.
export function getAdminHeaders() {
  return {};
}

export function getStudentHeaders() {
  return {};
}

export function clearStudentSession() {
  ["studentToken", "studentRollNo", "studentName", "studentExpiresAt"].forEach((k) =>
    sessionStorage.removeItem(k),
  );
}

export function clearAdminSession() {
  ["adminToken", "adminRole", "adminUsername", "adminDepartment", "adminDepartmentId", "adminExpiresAt"].forEach((k) =>
    sessionStorage.removeItem(k),
  );
}

// Best-effort server logout (expires the httpOnly cookie), then local cleanup. The logout
// endpoints are CSRF-exempt and succeed even with a lapsed session.
export async function logoutAdmin() {
  try {
    await api.post("/auth/logout");
  } catch {
    /* clear locally regardless */
  }
  clearAdminSession();
}

export async function logoutStudent() {
  try {
    await api.post("/student/auth/logout");
  } catch {
    /* clear locally regardless */
  }
  clearStudentSession();
}

// Student-scoped auth failures: a missing/expired student session goes back to login. Scoped by
// URL so admin flows (which handle their own 401s) are untouched; the login endpoint is excluded.
function isStudentScopedUrl(url = "") {
  if (url.includes("/student/auth/")) return false;
  // Load-bearing: admin endpoints are never student-scoped, but the admin student-management URLs
  // ("/admin/students/**") contain the substring "/student". Without excluding "/admin/" first, a
  // 401/403 on e.g. /admin/students/import would bounce the admin to the STUDENT login page.
  if (url.includes("/admin/")) return false;
  if (url.includes("/student")) return true;
  // POST /api/register (student submission), but not /register/verify (admin)
  if (url.includes("/register") && !url.includes("/register/verify")) return true;
  return false;
}

function isAdminScopedUrl(url = "") {
  if (url.includes("/auth/login")) return false; // the admin login call itself
  if (url.includes("/admin/")) return true; // /api/admin/**
  if (url.includes("/register/verify")) return true;
  return false;
}

// Centralized auth-failure handling: an expired/missing session sends the matching
// audience back to its login screen, scoped by URL so the two flows don't collide.
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const url = error.config?.url || "";
    // 401 = no valid session (the fixed 1h window lapsed, or the cookie is gone): sign out. The
    // server emits it via SecurityConfig's authenticationEntryPoint. 403 is deliberately NOT
    // handled here — it means "authenticated but denied", so the request rejects through and the
    // page shows the server's message in place instead of ejecting the user mid-task.
    if (status === 401) {
      if (isStudentScopedUrl(url)) {
        clearStudentSession();
        if (!window.location.pathname.startsWith("/student/login")) {
          window.location.assign("/student/login?expired=1");
        }
      } else if (isAdminScopedUrl(url)) {
        clearAdminSession();
        if (!window.location.pathname.startsWith("/admin/login")) {
          window.location.assign("/admin/login?expired=1");
        }
      }
    }
    return Promise.reject(error);
  },
);

export default api;
