// The catch-all route. Before it existed, any unmatched URL rendered an empty <body>: no message,
// no navigation, no way back — which is what a stale bookmark to a removed route landed on.
describe("unknown routes", () => {
  it("renders the not-found page instead of a blank screen", () => {
    cy.visit("/this/route/does/not/exist");
    cy.get('[data-cy="not-found-message"]').should("be.visible");
    cy.contains("This page doesn't exist").should("be.visible");
  });

  it("still catches a route that was removed from the app", () => {
    // /admin/add-subject was a real route until the subject tabs replaced it; bookmarks survive.
    cy.visit("/admin/add-subject");
    cy.get('[data-cy="not-found-message"]').should("be.visible");
  });

  it("offers working links to all three entry points", () => {
    cy.visit("/nope");
    cy.get('[data-cy="not-found-student"]').should("have.attr", "href", "/student/login");
    cy.get('[data-cy="not-found-admin"]').should("have.attr", "href", "/admin/login");
    cy.get('[data-cy="not-found-home"]').click();
    cy.location("pathname").should("eq", "/");
  });
});
