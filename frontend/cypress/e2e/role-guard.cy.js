// A signed-in staff account on a page its role may not use goes to /admin, and gets there WITHOUT
// touching the network on the way.
//
// This had no coverage before 2026-08-31, which is why three different guard idioms drifted apart
// unnoticed. Two pages sent a wrong-role user to /admin/login, where AdminLoginPage sees the live
// token and bounces straight back to /admin — so the visit worked, but it mounted the login page
// and fired a wasted `GET /api/departments` first. The path is identical either way, so a
// destination-only assertion could not see the difference; the request-count assertion is what
// makes this spec evidence rather than decoration.
//
// Modelled on proctor.cy.js's "cannot reach Manage Users" case, the one guard test that already
// existed.
describe("Wrong-role pages redirect to /admin without fetching", () => {
  // The redirect lands on /admin, which immediately loads the dashboard — stub it or its 401s
  // sign the session out and the assertions fail for an unrelated reason.
  const stubDashboard = () => {
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [], totalPages: 0, totalElements: 0, number: 0 },
    });
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
  };

  // The public list. AdminLoginPage fetches it on mount, so a non-zero count here is precisely the
  // symptom of a redirect that detoured through the login page.
  const watchPublicDepartments = () =>
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] }).as("publicDepartments");

  const DEPT_OFFICE = { role: "DEPT_OFFICE", username: "deptcse", department: "Computer Science" };

  it("exam cycles: a DEPT_OFFICE lands on /admin and never reaches the login page", () => {
    stubDashboard();
    watchPublicDepartments();

    cy.visitAsAdmin("/admin/exam-cycles", DEPT_OFFICE);

    cy.location("pathname").should("eq", "/admin");
    // the regression this guards: the old guard bounced via /admin/login, whose own mount fetched
    // this endpoint before sending the user back here
    cy.get("@publicDepartments.all").should("have.length", 0);
  });

  it("departments: a DEPT_OFFICE lands on /admin and never reaches the login page", () => {
    stubDashboard();
    watchPublicDepartments();
    cy.intercept("GET", "/api/admin/departments*", cy.spy().as("adminDepartments"));

    cy.visitAsAdmin("/admin/departments", DEPT_OFFICE);

    cy.location("pathname").should("eq", "/admin");
    cy.get("@publicDepartments.all").should("have.length", 0);
  });

  it("an allowed role is not redirected", () => {
    // Negative control. Without it these pass just as well with the guard replaced by an
    // unconditional redirect, which would lock every role out of every page.
    stubDashboard();
    cy.intercept("GET", "/api/admin/exam-cycles", { statusCode: 200, body: [] }).as("cycles");

    cy.visitAsAdmin("/admin/exam-cycles", { role: "ADMIN", username: "admin" });

    cy.location("pathname").should("eq", "/admin/exam-cycles");
    cy.contains("New Exam Cycle").should("be.visible");
  });
});
