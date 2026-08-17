// 403 handling — "authenticated but denied". The contract (SecurityConfig + api.js): 401 signs the
// user out; 403 renders in place and keeps them where they are.
//
// This spec exists because the suite had exactly ONE 403 stub across 14 files, which is how a
// regression survived unnoticed: api.js was fixed to stop treating 403 as a logout, but four call
// sites still did it themselves — AdminPage re-implemented the sign-out locally, and the two
// student pages swallowed 403 into a blank screen with no message at all.
describe("403 denials surface in place and never sign the user out", () => {
  const DENIED = {
    statusCode: 403,
    body: { message: "This registration does not belong to your department." },
  };

  describe("admin dashboard", () => {
    beforeEach(() => {
      cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
      cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
      cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
      cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
        statusCode: 200,
        body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
      });
    });

    it("shows the server's message and stays on the dashboard", () => {
      cy.intercept("GET", "/api/admin/registrations*", DENIED).as("denied");
      cy.visitAsAdmin("/admin", { role: "HOD", username: "hodcse", department: "Computer Science" });
      cy.wait("@denied");

      cy.get('[data-cy="admin-load-error"]')
        .should("be.visible")
        .and("contain", "does not belong to your department");
      // the regression: a 403 used to clear the session and bounce to /admin/login
      cy.location("pathname").should("eq", "/admin");
      cy.window().its("sessionStorage.adminToken").should("eq", "admin-jwt-token");
    });

    it("stops the loading spinner instead of hanging on it", () => {
      cy.intercept("GET", "/api/admin/registrations*", DENIED).as("denied");
      cy.visitAsAdmin("/admin", { role: "HOD", username: "hodcse", department: "Computer Science" });
      cy.wait("@denied");

      // setLoading(false) ran only on success before, so a failure left this up forever
      cy.contains("Loading registrations...").should("not.exist");
    });

    it("still signs out on a 401", () => {
      cy.intercept("GET", "/api/admin/registrations*", {
        statusCode: 401,
        body: { message: "Unknown account. Please sign in again." },
      }).as("expired");
      cy.visitAsAdmin("/admin", { role: "HOD", username: "hodcse", department: "Computer Science" });
      cy.wait("@expired");

      cy.location("pathname").should("eq", "/admin/login");
    });
  });

  describe("student pages", () => {
    it("dashboard: renders the denial instead of a blank page", () => {
      cy.intercept("GET", "/api/student/me", {
        statusCode: 403,
        body: { message: "Your account is not available for registration." },
      }).as("denied");
      cy.intercept("GET", "/api/student/registrations", { statusCode: 200, body: [] });

      cy.visitAsStudent("/student");
      cy.wait("@denied");

      cy.contains("not available for registration").should("be.visible");
      cy.location("pathname").should("eq", "/student");
    });

    it("registration page: renders the denial on profile load", () => {
      cy.intercept("GET", "/api/student/me", {
        statusCode: 403,
        body: { message: "Registration is closed for your department." },
      }).as("denied");
      cy.intercept("GET", "/api/student/subjects*", { statusCode: 200, body: [] });
      cy.intercept("GET", "/api/registration-status", {
        statusCode: 200,
        body: { open: true },
      });

      cy.visitAsStudent("/register");
      cy.wait("@denied");

      cy.contains("closed for your department").should("be.visible");
      cy.location("pathname").should("eq", "/register");
    });
  });
});
