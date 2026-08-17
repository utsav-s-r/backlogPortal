beforeEach(() => {
  cy.clearLocalStorage();
  cy.clearAllSessionStorage();
});

// The one definition of "a signed-in admin". Specs never drive the login UI, so each one used to
// hand-write the sessionStorage seed inside `cy.visit(..., { onBeforeLoad })` — 49 setItem lines
// across 14 files, four of which had already re-derived the same (role, department) helper
// independently. Keeping it in one place is also what made it visible that the copies seeded only
// a SUBSET of the six keys the app reads (api.js:43); `adminDepartmentId` was seeded by none of
// them, leaving findOwnDepartment's id-match branch — the fix for the silent dept-rename bug —
// with zero coverage.
//
// It wraps `cy.visit` rather than being a bare `cy.seedAdmin()`: the keys must exist BEFORE the
// app boots (ProtectedAdminRoute and every page read sessionStorage during render), and a queued
// Cypress command cannot return the `onBeforeLoad` callback that requires.
//
// Session fields are named; everything else passes through to cy.visit (e.g. failOnStatusCode).
// Student counterpart. Same reasoning and same wrapping trick; a separate command rather than a
// `scope` flag on one because the two audiences share no keys (api.js:37 vs :43) and nothing about
// a student session is dept-scoped.
Cypress.Commands.add("visitAsStudent", (path, options = {}) => {
  const {
    rollNo = "1MS24CS001",
    name = "Test Student",
    minutesLeft,
    onBeforeLoad,
    ...visitOptions
  } = options;

  cy.visit(path, {
    ...visitOptions,
    onBeforeLoad(win) {
      const ss = win.sessionStorage;
      // Presence marker, as on the admin side. No spec asserts this literal.
      ss.setItem("studentToken", "student-jwt-token");
      ss.setItem("studentRollNo", rollNo);
      ss.setItem("studentName", name);
      if (minutesLeft != null) {
        ss.setItem("studentExpiresAt", String(Date.now() + minutesLeft * 60 * 1000));
      }
      onBeforeLoad?.(win);
    },
  });
});

Cypress.Commands.add("visitAsAdmin", (path, options = {}) => {
  const {
    role = "ADMIN",
    username = role.toLowerCase(),
    department,
    departmentId,
    minutesLeft,
    onBeforeLoad,
    ...visitOptions
  } = options;

  cy.visit(path, {
    ...visitOptions,
    onBeforeLoad(win) {
      const ss = win.sessionStorage;
      ss.setItem("adminRole", role);
      // A presence marker, not a JWT — the real token is in an httpOnly cookie (api.js:19).
      // session-lifecycle.cy.js asserts this literal, so it is a fixture value, not an arbitrary one.
      ss.setItem("adminToken", "admin-jwt-token");
      ss.setItem("adminUsername", username);
      // Absent, not empty: the app distinguishes "no department" (ADMIN/PRINCIPAL) from one set.
      if (department) ss.setItem("adminDepartment", department);
      if (departmentId != null) ss.setItem("adminDepartmentId", String(departmentId));
      if (minutesLeft != null) {
        ss.setItem("adminExpiresAt", String(Date.now() + minutesLeft * 60 * 1000));
      }
      // Chained, not dropped: a caller's own onBeforeLoad would otherwise be silently overwritten
      // by this one.
      onBeforeLoad?.(win);
    },
  });
});

// Names the endpoint when an unstubbed admin call sinks a test (it 401s and api.js signs the
// session out mid-test). A DIAGNOSTIC, not a gate — it catches nothing extra. Defined FIRST so
// spec-level intercepts win and this sees only genuine gaps.
// The matcher must stay the GLOB with the scoping in JS: a RouteMatcher regex silently matches
// NOTHING, installing clean and never firing. Scope mirrors api.js's isAdminScopedUrl — widening
// it to all of /api only fabricates failures on the public endpoints.
// It can't be a gate: it only NAMES the endpoint once an unstubbed call already broke a spec, so
// it catches nothing extra and is no substitute for stubbing every endpoint the page loads.
beforeEach(() => {
  cy.intercept({ url: "**/api/**" }, (req) => {
    if (!/\/api\/(admin\/|register\/verify)/.test(req.url)) return req.continue();
    throw new Error(
      `Unstubbed admin API call: ${req.method} ${req.url}\n` +
        `It will 401 and sign the session out mid-test. Add a cy.intercept for it.`
    );
  });
});
