// A load that 401s means the session lapsed and api.js is redirecting to the login screen. Until
// that lands the page must show NOTHING new — no error banner, no empty-state copy claiming the
// account has no data. Both used to paint for a beat: the banner blamed the server for an expired
// session, and "No users you can manage yet" read as a permissions verdict.
//
// NO DOM ASSERTION FOR THE 401 FLASH, deliberately: it lives in the frame between
// window.location.assign() and navigation committing. Cypress cannot pin that frame —
// location.assign is non-configurable, so cy.stub throws "Cannot redefine property: assign" — and
// post-navigation the banner is gone either way. Verified the hard way: an earlier version asserted
// `.should("not.exist")` and stayed green with the bug deliberately reintroduced. An assertion that
// cannot fail is worse than none, so the decision is asserted directly below and the DOM specs
// cover only what is genuinely observable.
import { reportLoadError } from "../../src/lib/loadError";

const EXPIRED = { statusCode: 401, body: { message: "Token expired" } };

describe("reportLoadError decides which failures a page may show", () => {
  function spy() {
    const calls = [];
    const fn = (msg) => calls.push(msg);
    fn.calls = calls;
    return fn;
  }

  it("stays silent on a 401 — api.js is signing out, and the caller must keep its spinner up", () => {
    const setError = spy();
    expect(reportLoadError({ response: { status: 401 } }, setError, "fallback")).to.equal(false);
    expect(setError.calls).to.have.length(0);
  });

  it("stays silent on a superseded request", () => {
    const setError = spy();
    expect(reportLoadError({ code: "ERR_CANCELED" }, setError, "fallback")).to.equal(false);
    expect(setError.calls).to.have.length(0);
  });

  it("reports every other failure, preferring the server's own message", () => {
    const setError = spy();
    const err = { response: { status: 403, data: { message: "No department assigned" } } };
    expect(reportLoadError(err, setError, "fallback")).to.equal(true);
    expect(setError.calls).to.deep.equal(["No department assigned"]);
  });

  it("falls back when the server sent no message", () => {
    const setError = spy();
    expect(reportLoadError({ response: { status: 500 } }, setError, "fallback")).to.equal(true);
    expect(setError.calls).to.deep.equal(["fallback"]);
  });
});

describe("An expired session ends in a sign-out, not a stuck page", () => {
  it("exam cycles: a 401 during load redirects to the admin login", () => {
    cy.intercept("GET", "/api/admin/exam-cycles", EXPIRED).as("cycles");
    cy.visitAsAdmin("/admin/exam-cycles");
    cy.wait("@cycles");

    cy.location("pathname").should("eq", "/admin/login");
  });

  it("manage users: a 401 during load redirects to the admin login", () => {
    cy.intercept("GET", "/api/admin/users", EXPIRED).as("users");
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] });
    cy.visitAsAdmin("/admin/users");
    cy.wait("@users");

    cy.location("pathname").should("eq", "/admin/login");
  });
});

describe("A real failure is still visible — the 401 guard is scoped, not a blanket mute", () => {
  it("exam cycles: a 500 shows the message", () => {
    cy.intercept("GET", "/api/admin/exam-cycles", { statusCode: 500, body: {} }).as("cycles");
    cy.visitAsAdmin("/admin/exam-cycles");
    cy.wait("@cycles");

    cy.contains("Could not load exam cycles").should("be.visible");
  });

  it("departments: a 500 shows the message", () => {
    cy.intercept("GET", "/api/admin/departments", { statusCode: 500, body: {} }).as("depts");
    cy.visitAsAdmin("/admin/departments");
    cy.wait("@depts");

    cy.contains("Could not load departments").should("be.visible");
  });

  it("manage users: a 500 shows the failure instead of claiming an empty list", () => {
    cy.intercept("GET", "/api/admin/users", { statusCode: 500, body: {} }).as("users");
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] });
    cy.visitAsAdmin("/admin/users");
    cy.wait("@users");

    cy.contains("Could not load users").should("be.visible");
    cy.contains("No users you can manage yet").should("not.exist");
  });

  it("manage users: the departments warning has its own slot and outlives a submit", () => {
    cy.intercept("GET", "/api/admin/users", { statusCode: 200, body: [] }).as("users");
    cy.intercept("GET", "/api/departments", { statusCode: 500, body: {} }).as("depts");
    cy.visitAsAdmin("/admin/users");
    cy.wait(["@users", "@depts"]);

    cy.get('[data-cy="users-departments-error"]').should("be.visible");

    // submitting with an empty username sets `error` — which used to erase this warning
    cy.get('[aria-label="Create user"]').click();
    cy.get('[data-cy="users-departments-error"]').should("be.visible");
  });

  it("admin dashboard: a failed filter-options fetch says so instead of showing empty dropdowns", () => {
    cy.intercept("GET", "/api/admin/departments*", { statusCode: 500, body: {} }).as("depts");
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 500, body: {} });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [], totalPages: 0, totalElements: 0, number: 0 },
    });
    // Defined after the list intercept so it wins for the /summary-counts sub-path. Load-bearing,
    // not tidiness: unstubbed it reaches the dev server, 401s, and api.js correctly signs the admin
    // out — navigating away from /admin mid-assertion. That raced this test's banner lookup and
    // failed it ~3 runs in 5, looking like an app bug in the error path.
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
    });
    cy.visitAsAdmin("/admin");
    cy.wait("@depts");

    cy.get('[data-cy="admin-filter-options-error"]').should("be.visible");
  });
});
