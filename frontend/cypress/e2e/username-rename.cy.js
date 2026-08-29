/**
 * Renaming an account — the ADMIN-only action on Manage Users, and the self-service form on
 * Account Settings.
 *
 * The page loads /api/admin/users and /api/departments, so BOTH are stubbed in every test: an
 * unstubbed /api/admin/** call is proxied to :8080, 401s, and api.js correctly signs the session
 * out mid-test, which then fails on an unrelated assertion, intermittently. /api/departments is
 * public and matches no redirect scope, but is stubbed anyway to keep the list deterministic.
 *
 * What Cypress can and cannot prove here: the role gating asserted below is the UI MIRROR only.
 * The control is PATCH /api/admin/users/{username}'s @PreAuthorize, covered (and mutation-tested)
 * in UserRenameAuthorizationTest — no spec can observe a 403 when every call is stubbed.
 */

const USERS = [
  { username: "hod_cs", role: "HOD", departmentName: "Computer Science and Engineering" },
  { username: "office_cs", role: "DEPT_OFFICE", departmentName: "Computer Science and Engineering" },
];

const DEPARTMENTS = [
  { id: 1, code: "CS", deptName: "Computer Science and Engineering" },
];

function stubUsersPage() {
  cy.intercept("GET", "/api/admin/users", { statusCode: 200, body: USERS }).as("users");
  cy.intercept("GET", "/api/departments", { statusCode: 200, body: DEPARTMENTS }).as("depts");
}

describe("Manage Users — renaming somebody else is ADMIN only", () => {
  it("ADMIN sees Rename and the PATCH carries the new name", () => {
    stubUsersPage();
    cy.intercept("PATCH", "/api/admin/users/hod_cs", {
      statusCode: 200,
      body: { username: "hod_cse", role: "HOD" },
    }).as("rename");

    cy.visitAsAdmin("/admin/users", { role: "ADMIN", username: "admin" });
    cy.wait(["@users", "@depts"]);

    cy.window().then((win) => cy.stub(win, "prompt").returns("hod_cse"));
    cy.contains("tr", "hod_cs").contains("button", "Rename").click();

    cy.wait("@rename").its("request.body").should("deep.equal", { newUsername: "hod_cse" });

    // The banner must NOT reuse the create/reset wording, which asserts the password is
    // `username4321` — a rename leaves the password alone, so that would be a false statement.
    cy.contains("Account renamed").should("be.visible");
    cy.contains("Their password is unchanged").should("be.visible");
    cy.contains("hod_cse4321").should("not.exist");
  });

  it("PRINCIPAL is not offered Rename", () => {
    stubUsersPage();
    cy.visitAsAdmin("/admin/users", { role: "PRINCIPAL", username: "principal" });
    cy.wait(["@users", "@depts"]);

    cy.contains("tr", "hod_cs").should("be.visible");
    cy.contains("button", "Rename").should("not.exist");
    // the ladder's other actions are still there — this is a rename-specific denial, not a
    // read-only page
    cy.contains("tr", "hod_cs").contains("button", "Reset").should("be.visible");
  });

  it("HOD is not offered Rename", () => {
    stubUsersPage();
    cy.visitAsAdmin("/admin/users", {
      role: "HOD",
      username: "hod_cs",
      department: "Computer Science and Engineering",
      departmentId: 1,
    });
    cy.wait(["@users", "@depts"]);

    cy.contains("button", "Rename").should("not.exist");
  });

  it("a refused rename shows the server's message and leaves the list alone", () => {
    stubUsersPage();
    cy.intercept("PATCH", "/api/admin/users/hod_cs", {
      statusCode: 409,
      body: { message: "A user with that username already exists" },
    }).as("rename");

    cy.visitAsAdmin("/admin/users", { role: "ADMIN", username: "admin" });
    cy.wait(["@users", "@depts"]);

    cy.window().then((win) => cy.stub(win, "prompt").returns("office_cs"));
    cy.contains("tr", "hod_cs").contains("button", "Rename").click();
    cy.wait("@rename");

    cy.contains("A user with that username already exists").should("be.visible");
    cy.contains("Account renamed").should("not.exist");
    cy.contains("tr", "hod_cs").should("be.visible");
  });
});

describe("Account Settings — renaming yourself", () => {
  it("states up front that it signs you out, then does", () => {
    cy.intercept("POST", "/api/auth/change-username", {
      statusCode: 200,
      body: { message: "Username changed. Please sign in again.", signedOut: "true" },
    }).as("rename");

    cy.visitAsAdmin("/admin/change-password", { role: "HOD", username: "hod_cs" });

    // the warning is on the page BEFORE submitting — discovered-after-the-fact is the failure mode
    cy.contains("signs you out immediately").should("be.visible");

    cy.get("#new-username").type("hod_cse");
    cy.get("#rename-password").type("current-pass");
    cy.get('[aria-label="Change username"]').click();

    cy.wait("@rename").its("request.body").should("deep.equal", {
      currentPassword: "current-pass",
      newUsername: "hod_cse",
    });

    cy.location("pathname").should("eq", "/admin/login");
    // the cached identity must go too, or the next page renders a stale role
    cy.window().then((win) => {
      expect(win.sessionStorage.getItem("adminUsername")).to.be.null;
      expect(win.sessionStorage.getItem("adminRole")).to.be.null;
    });
  });

  it("a wrong password is reported in place and keeps the session", () => {
    cy.intercept("POST", "/api/auth/change-username", {
      statusCode: 400,
      body: { message: "Current password is incorrect" },
    }).as("rename");

    cy.visitAsAdmin("/admin/change-password", { role: "HOD", username: "hod_cs" });

    cy.get("#new-username").type("hod_cse");
    cy.get("#rename-password").type("wrong");
    cy.get('[aria-label="Change username"]').click();
    cy.wait("@rename");

    cy.contains("Current password is incorrect").should("be.visible");
    cy.location("pathname").should("eq", "/admin/change-password");
    cy.window().then((win) => {
      expect(win.sessionStorage.getItem("adminUsername")).to.eq("hod_cs");
    });
  });

  it("rejects a too-short username without calling the server", () => {
    cy.intercept("POST", "/api/auth/change-username", cy.spy().as("renameCall"));

    cy.visitAsAdmin("/admin/change-password", { role: "HOD", username: "hod_cs" });

    cy.get("#new-username").type("ab");
    cy.get("#rename-password").type("current-pass");
    cy.get('[aria-label="Change username"]').click();

    cy.contains("Username must be at least 4 characters").should("be.visible");
    cy.get("@renameCall").should("not.have.been.called");
  });
});
