// The dashboard's terminal denial: `adminRole` absent or unrecognised.
//
// AdminPage renders a denial instead of calling useRoleGuard — /admin IS that hook's redirect
// target, so redirecting would loop. The branch had no coverage, which is how a redundant
// `|| !adminToken` conjunct survived here long after ProtectedAdminRoute made the marker
// impossible to be missing.
//
// SCOPE: the render decision (`if (!isAdmin)`) and nothing more. Mutation-tested — `if (false)`
// fails both tests.
//
// A "denied session issues no admin request" assertion was tried and DELIBERATELY REMOVED; do not
// re-add it in this shape. fetchRegistrations aborts its predecessor every re-render, so whether a
// fired-then-aborted request lands in `cy.get("@alias.all")` is a race — against a broken guard the
// same spec caught the mutation one run and passed the next, even with a 600ms settle. An
// intermittently-passing network assertion reads as coverage the suite lacks. Proving it needs an
// instrument that records the request at issue time, independent of the abort.
describe("Admin dashboard — unrecognised role is denied", () => {
  // Stubbed so a regressed guard's calls don't 401, sign the session out, and fail elsewhere.
  const stubAdminCalls = () => {
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [], totalPages: 0, totalElements: 0, number: 0 },
    });
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
  };

  const assertDenied = () => {
    cy.contains("h1", "Access Denied").should("be.visible");
    // /admin/login, never /admin: terminal, so the way out can't be the refused page.
    cy.contains("a", "Go to Admin Login").should("have.attr", "href", "/admin/login");
    // Dashboard must not sit behind the card: no filter panel, no stat cards.
    cy.get('[data-cy="admin-search"]').should("not.exist");
    cy.contains("Total").should("not.exist");
  };

  it("denies a role the app does not recognise", () => {
    stubAdminCalls();
    // A role outside UserRole — one of the only two shapes reaching this branch.
    cy.visitAsAdmin("/admin", { role: "NOT_A_ROLE" });
    assertDenied();
  });

  it("denies an absent role the same way", () => {
    stubAdminCalls();
    // The other: `adminRole` empty, as a half-written session leaves it.
    cy.visitAsAdmin("/admin", { role: "", username: "nobody" });
    assertDenied();
  });
});
