// The app-wide error boundary. Before it existed, any throw during render — or a lazy page chunk
// that failed to load — left a completely blank page with nothing in the UI to act on.
//
// This forces the real failure rather than mocking our own component: the e2e run serves through
// the Vite dev server, where a lazily-imported page resolves to its own module URL, so failing that
// request reproduces a dropped-network mid-navigation exactly.
describe("error boundary", () => {
  it("shows a recoverable message when a page chunk fails to load", () => {
    cy.intercept("GET", "/src/pages/AdminPage.jsx*", { statusCode: 500, body: "" }).as("chunk");

    cy.visitAsAdmin("/admin", {
      // the module failure surfaces as an uncaught exception; the boundary is what we're testing
      failOnStatusCode: false,
    });

    cy.get('[data-cy="error-boundary"]').should("be.visible");
    cy.get('[data-cy="error-boundary-reload"]').should("be.visible");
    cy.get('[data-cy="error-boundary-home"]').should("have.attr", "href", "/");
  });

  it("does not appear on a healthy page", () => {
    cy.visit("/");
    cy.get('[data-cy="error-boundary"]').should("not.exist");
  });
});
