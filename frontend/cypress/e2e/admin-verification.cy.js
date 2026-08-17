// The admin list is server-paginated: the dashboard reads a Spring `Page` envelope
// ({ content, totalElements, totalPages, number }) and, after a verify/reject, refetches the page
// plus summary-counts — status filtering is server-side, so an actioned row is re-read rather than
// mutated in place. Hence the registrations stub is stateful wherever an action must change what
// the refetch returns.
describe("Admin verification flow", () => {
  const row = (overrides = {}) => ({
    regId: "REG-2026-1001",
    rollNo: "1MS22CS001",
    studentName: "Student One",
    semester: 4,
    yearOfJoining: 2022,
    subjects: ["Data Structures"],
    status: "SUBMITTED",
    verifiedBy: null,
    registeredAt: "2026-04-20T10:20:00",
    ...overrides,
  });
  const pageOf = (rows) => ({
    content: rows,
    totalElements: rows.length,
    totalPages: rows.length ? 1 : 0,
    number: 0,
  });
  const stubCounts = () =>
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 1, submitted: 1, verified: 0, rejected: 0 },
    });
  const stubSideCalls = () => {
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
  };

  // A failed counts fetch used to leave the initial zeros on screen: four confident zeros above a
  // table full of rows, reading as "the queue is empty".
  it("shows a dash, not zero, when the totals fail to load", () => {
    cy.intercept("GET", "/api/admin/registrations*", { statusCode: 200, body: pageOf([row()]) });
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 500,
      body: { message: "Counts backend is down." },
    }).as("counts");
    stubSideCalls();

    cy.visitAsAdmin("/admin");
    cy.wait("@counts");

    cy.get('[data-cy="admin-counts-error"]').should("contain", "Counts backend is down.");
    cy.contains("Total").parent().should("contain", "—").and("not.contain", "0");
    // the table itself is unaffected
    cy.contains("1MS22CS001").should("be.visible");
  });

  // A failed exam-cycle fetch left examCycleId empty, which the export read as "every cycle".
  it("refuses to export when the cycle list failed and warns the list is unscoped", () => {
    cy.intercept("GET", "/api/admin/registrations*", { statusCode: 200, body: pageOf([row()]) });
    stubCounts();
    // stubSideCalls first, then override exam-cycles — Cypress resolves intercepts most-recent-first
    stubSideCalls();
    cy.intercept("GET", "/api/admin/exam-cycles*", {
      statusCode: 500,
      body: { message: "Cycle service unavailable." },
    }).as("cycles");
    cy.intercept("POST", "/api/admin/export-pdf", { statusCode: 200, body: {} }).as("export");

    cy.visitAsAdmin("/admin");
    cy.wait("@cycles");

    cy.get('[data-cy="admin-cycles-error"]').should("contain", "Cycle service unavailable.");
    cy.get('[data-cy="admin-scope-warning"]').should("be.visible");

    cy.get('[data-cy="admin-export-pdf"]').click();
    cy.get('[data-cy="admin-export-error"]').should("contain", "scope is unknown");
    // the crucial part: no unscoped PDF was ever requested
    cy.get("@export.all").should("have.length", 0);
  });

  // Regression guard: the warning is driven by a THREE-state cycles status, not a boolean. With a
  // boolean it was true from first paint, so this banner claimed "the cycle list failed to load"
  // on every single admin page load while the request was still in flight.
  it("never shows the unscoped warning when the cycles load fine", () => {
    cy.intercept("GET", "/api/admin/registrations*", { statusCode: 200, body: pageOf([row()]) });
    stubCounts();
    stubSideCalls(); // exam-cycles returns 200 with []
    cy.intercept("POST", "/api/admin/export-pdf", {
      statusCode: 200,
      headers: { "content-type": "application/pdf" },
      body: "%PDF-1.4",
    }).as("export");

    cy.visitAsAdmin("/admin");
    cy.contains("1MS22CS001").should("be.visible");
    cy.get('[data-cy="admin-scope-warning"]').should("not.exist");
    cy.get('[data-cy="admin-cycles-error"]').should("not.exist");

    // an empty-but-LOADED cycle list is a real "all cycles" answer, so export still works
    cy.get('[data-cy="admin-export-pdf"]').click();
    cy.wait("@export").its("request.body.allCycles").should("eq", true);
  });

  // Ticked rows name their own regIds, so they stay exportable even with the cycle list down.
  it("still exports ticked rows when the cycle list failed", () => {
    cy.intercept("GET", "/api/admin/registrations*", { statusCode: 200, body: pageOf([row()]) });
    stubCounts();
    stubSideCalls();
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 500, body: {} });
    cy.intercept("POST", "/api/admin/export-pdf", {
      statusCode: 200,
      headers: { "content-type": "application/pdf" },
      body: "%PDF-1.4",
    }).as("export");

    cy.visitAsAdmin("/admin");
    cy.get('[data-cy="admin-select-REG-2026-1001"]').check();
    cy.get('[data-cy="admin-export-pdf"]').click();

    cy.wait("@export").its("request.body").should("deep.equal", { regIds: ["REG-2026-1001"] });
  });

  it("logs in as admin and verifies pending registration", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");

    // stateful: the post-verify refetch must reflect the new status
    let verified = false;
    cy.intercept("GET", "/api/admin/registrations*", (req) => {
      req.reply({
        statusCode: 200,
        body: pageOf([
          row(verified ? { status: "VERIFIED", verifiedBy: "admin" } : {}),
        ]),
      });
    }).as("getRegistrations");
    stubCounts();
    stubSideCalls();

    cy.intercept("PUT", "/api/register/verify/REG-2026-1001", (req) => {
      verified = true;
      req.reply({
        statusCode: 200,
        body: {
          regId: "REG-2026-1001",
          studentName: "Student One",
          rollNo: "1MS22CS001",
          status: "VERIFIED",
        },
      });
    }).as("verifyRegistration");

    cy.visit("/admin/login");
    // the login page gates the credential form behind a designation selection; the real role
    // comes from the mocked login response above
    cy.contains("Principal / Registrar / COE").click();
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();

    cy.wait("@adminLogin");
    cy.wait("@getRegistrations");

    cy.get('[data-cy="admin-filter-submitted"]').click();
    cy.get('[data-cy="admin-verify"]').first().click();

    cy.wait("@verifyRegistration");
    // refetched row now reads VERIFIED; its action buttons are gone
    cy.contains("td", "Verified").should("exist");
    cy.get('[data-cy="admin-verify"]').should("not.exist");
  });

  it("logs in as admin and rejects a pending registration", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");

    let rejected = false;
    cy.intercept("GET", "/api/admin/registrations*", (req) => {
      req.reply({
        statusCode: 200,
        body: pageOf([
          row(rejected ? { status: "REJECTED", verifiedBy: "admin" } : {}),
        ]),
      });
    }).as("getRegistrations");
    stubCounts();
    stubSideCalls();

    cy.intercept("PUT", "/api/register/verify/REG-2026-1001", (req) => {
      rejected = true;
      req.reply({
        statusCode: 200,
        body: {
          regId: "REG-2026-1001",
          studentName: "Student One",
          rollNo: "1MS22CS001",
          status: "REJECTED",
        },
      });
    }).as("rejectRegistration");

    cy.visit("/admin/login");
    cy.contains("Administrator").click();
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();
    cy.wait("@adminLogin");
    cy.wait("@getRegistrations");

    cy.get('[data-cy="admin-reject"]').first().click();

    // the PUT carries action REJECTED and the refetched row flips to Rejected
    cy.wait("@rejectRegistration")
      .its("request.body")
      .should("deep.equal", { action: "REJECTED" });
    cy.contains("td", "Rejected").should("exist");
    cy.get('[data-cy="admin-reject"]').should("not.exist");
  });

  it("rejects an already-VERIFIED registration via the two-step arm→confirm control", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");

    // row starts VERIFIED; the post-reject refetch flips it to REJECTED
    let rejected = false;
    cy.intercept("GET", "/api/admin/registrations*", (req) => {
      req.reply({
        statusCode: 200,
        body: pageOf([
          row({
            status: rejected ? "REJECTED" : "VERIFIED",
            verifiedBy: "admin",
          }),
        ]),
      });
    }).as("getRegistrations");
    stubCounts();
    stubSideCalls();

    cy.intercept("PUT", "/api/register/verify/REG-2026-1001", (req) => {
      rejected = true;
      req.reply({
        statusCode: 200,
        body: {
          regId: "REG-2026-1001",
          studentName: "Student One",
          rollNo: "1MS22CS001",
          status: "REJECTED",
        },
      });
    }).as("rejectRegistration");

    cy.visit("/admin/login");
    cy.contains("Administrator").click();
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();
    cy.wait("@adminLogin");
    cy.wait("@getRegistrations");

    // arm: first click reveals Confirm/Cancel and does NOT fire the request yet
    cy.get('[data-cy="admin-reject-verified"]').first().click();
    cy.get('[data-cy="admin-reject-verified-confirm"]').should("exist");

    // confirm: the PUT carries action REJECTED and the refetched row flips
    cy.get('[data-cy="admin-reject-verified-confirm"]').click();
    cy.wait("@rejectRegistration")
      .its("request.body")
      .should("deep.equal", { action: "REJECTED" });
    cy.contains("td", "Rejected").should("exist");
    cy.get('[data-cy="admin-reject-verified"]').should("not.exist");
  });

  it("shows an inline per-row error when verify fails (no rollback needed)", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");

    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: pageOf([row()]),
    }).as("getRegistrations");
    stubCounts();
    stubSideCalls();

    // A non-conflict failure (500): server state didn't move, so expect an inline error with the
    // row still SUBMITTED and its buttons intact.
    cy.intercept("PUT", "/api/register/verify/REG-2026-1001", {
      statusCode: 500,
      body: { message: "Verification service unavailable" },
    }).as("verifyFails");

    cy.visit("/admin/login");
    cy.contains("Administrator").click();
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();

    cy.wait("@adminLogin");
    cy.wait("@getRegistrations");

    cy.get('[data-cy="admin-verify"]').first().click();
    cy.wait("@verifyFails");

    // inline error surfaced, no alert(), row not rolled forward
    cy.get('[data-cy="admin-action-error"]').should(
      "contain",
      "Verification service unavailable",
    );
    cy.get('[data-cy="admin-verify"]').should("exist");
    cy.contains("td", "Verified").should("not.exist");
  });

  it("resyncs the row from the server when verify hits a 409 conflict", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");

    // The row reads SUBMITTED until the conflicting PUT fires; the post-conflict resync then
    // returns it already VERIFIED, as if another admin actioned it underneath us. Keyed off the
    // PUT rather than a call counter, so it survives however many initial fetches the page makes
    // (StrictMode, filters).
    let conflictHit = false;
    cy.intercept("GET", "/api/admin/registrations*", (req) => {
      req.reply({
        statusCode: 200,
        body: pageOf([
          row(
            conflictHit
              ? { status: "VERIFIED", verifiedBy: "otheradmin" }
              : {},
          ),
        ]),
      });
    }).as("getRegistrations");
    stubCounts();
    stubSideCalls();

    cy.intercept("PUT", "/api/register/verify/REG-2026-1001", (req) => {
      conflictHit = true;
      req.reply({
        statusCode: 409,
        body: { message: "Already actioned by another admin" },
      });
    }).as("verifyConflict");

    cy.visit("/admin/login");
    cy.contains("Administrator").click();
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();

    cy.wait("@adminLogin");
    cy.wait("@getRegistrations"); // initial SUBMITTED load

    cy.get('[data-cy="admin-verify"]').first().click();
    cy.wait("@verifyConflict");
    cy.wait("@getRegistrations"); // automatic resync after the 409

    // row now reflects true server state: verified, action buttons gone
    cy.contains("td", "Verified").should("exist");
    cy.get('[data-cy="admin-verify"]').should("not.exist");
  });

  it("redirects unauthenticated visitors to the admin login", () => {
    cy.visit("/admin");
    cy.location("pathname").should("eq", "/admin/login");
    cy.location("search").should("contain", "redirect");
  });

  it("logs in via the Administrator path", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: pageOf([]),
    }).as("getRegistrations");
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
    });
    stubSideCalls();

    cy.visit("/admin/login");
    cy.contains("Administrator").click();

    // ADMIN has no department step
    cy.get('[data-cy="admin-department"]').should("not.exist");
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();

    cy.wait("@adminLogin");
    cy.location("pathname").should("eq", "/admin");
  });
});
