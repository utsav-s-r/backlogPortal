// Sessions are a fixed 1h and cannot be renewed. Two things must hold, and neither had any
// coverage before: a 401 signs the user out, while a 403 (authenticated but denied) leaves them
// exactly where they are with the server's message. The old interceptor treated both as a dead
// session, which is what made every scope denial log an admin out mid-task.
describe("Session lifecycle — expiry, warning banner, 401 vs 403", () => {
  const stubDashboard = () => {
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [], totalElements: 0, totalPages: 1, number: 0 },
    });
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
  };

  it("shows no warning banner with a full hour left", () => {
    stubDashboard();
    cy.visitAsAdmin("/admin", { minutesLeft: 60 });
    cy.get('[data-cy="session-warning"]').should("not.exist");
  });

  it("warns inside the final 10 minutes, with the minutes remaining", () => {
    stubDashboard();
    cy.visitAsAdmin("/admin", { minutesLeft: 7 });
    cy.get('[data-cy="session-warning"]')
      .should("be.visible")
      .and("contain", "7 minutes")
      .and("contain", "can't be extended");
  });

  it("signs out and returns to login when the session runs out", () => {
    stubDashboard();
    // already expired: the timer fires at 0 rather than waiting
    cy.visitAsAdmin("/admin", { minutesLeft: -1 });
    cy.location("pathname").should("eq", "/admin/login");
    cy.location("search").should("eq", "?expired=1");
    cy.get('[data-cy="session-expired"]').should("be.visible");
    cy.window().then((win) => {
      expect(win.sessionStorage.getItem("adminToken")).to.be.null;
      expect(win.sessionStorage.getItem("adminExpiresAt")).to.be.null;
    });
  });

  it("signs out on a 401 from the server", () => {
    stubDashboard();
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 401,
      body: { message: "Session expired. Please sign in again." },
    });
    cy.visitAsAdmin("/admin", { minutesLeft: 60 });
    cy.location("pathname").should("eq", "/admin/login");
    cy.get('[data-cy="session-expired"]').should("be.visible");
  });

  it("does NOT sign out on a 403 — the user stays put", () => {
    stubDashboard();
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 403,
      body: { message: "Outside your department's scope." },
    });
    cy.visitAsAdmin("/admin", { minutesLeft: 60 });
    // the regression this guards: a scope denial used to clear the session and bounce to login
    cy.location("pathname").should("eq", "/admin");
    cy.window().then((win) => {
      expect(win.sessionStorage.getItem("adminToken")).to.eq("admin-jwt-token");
    });
  });
});
