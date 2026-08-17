// The hero badge mirrors GET /api/registration-status (the active exam cycle). It must never claim
// registrations are open unconfirmed — the error case falls closed, like the registration page.

describe("Home hero cycle badge", () => {
  it("shows the active cycle name when registrations are open", () => {
    cy.intercept("GET", "/api/registration-status", {
      open: true,
      cycleName: "June 2026 Cycle",
      examMonthYear: "June 2026",
    }).as("regStatus");

    cy.visit("/");
    cy.wait("@regStatus");
    cy.contains("June 2026 Cycle — Registrations Open").should("be.visible");
  });

  it("shows the closed badge when no cycle is active", () => {
    cy.intercept("GET", "/api/registration-status", { open: false }).as("regStatus");

    cy.visit("/");
    cy.wait("@regStatus");
    cy.contains("Registrations Currently Closed").should("be.visible");
  });

  it("fails closed when the status endpoint errors", () => {
    cy.intercept("GET", "/api/registration-status", { statusCode: 500, body: {} }).as("regStatus");

    cy.visit("/");
    cy.wait("@regStatus");
    cy.contains("Registrations Currently Closed").should("be.visible");
    cy.contains("Registrations Open").should("not.exist");
  });
});
