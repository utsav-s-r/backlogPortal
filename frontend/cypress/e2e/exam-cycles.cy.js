// The college-wide registration switch: create / activate / end a cycle.
//
// High stakes: GET /api/registration-status and RegistrationService#register both key off the
// active cycle, so a regression here locks students out of registering or leaves registration open
// past its close. A spec that stubs the list as `[]` or a 401 leaves every per-cycle control
// unreachable and covers none of that.
//
// SCOPE: behaviour, not authorization. Role gating is role-guard.cy.js (DEPT_OFFICE redirected);
// the real control is ExamCycleController's class-level @PreAuthorize("hasRole('ADMIN')"), which
// no spec can observe with every call stubbed.
//
// FAKE SERVER, not fixed stubs: the page never patches its list locally — create/activate/end each
// re-GET and re-render, so fixed stubs would return the pre-action list and every assertion would
// have to assert the failure. State in `server` lets these assert the round trip. It mirrors one
// server rule deliberately — activate closes whatever is open first (ExamCycleController.activate),
// never >1 active. That rule is the server's and NOT under test; what is: the UI reflects reloaded
// state rather than toggling a row locally, which is how a second "Active" badge would appear.
describe("Exam cycles — the registration switch", () => {
  let server;

  const idFrom = (url) => Number(url.match(/exam-cycles\/(\d+)\//)[1]);

  const stubExamCycles = (initial) => {
    server = { cycles: initial, nextId: 100 };

    cy.intercept("GET", "/api/admin/exam-cycles", (req) =>
      req.reply({ statusCode: 200, body: server.cycles }),
    ).as("list");

    cy.intercept("POST", "/api/admin/exam-cycles", (req) => {
      const created = {
        id: server.nextId++,
        name: req.body.name,
        examMonthYear: req.body.examMonthYear,
        active: false,
        createdAt: "2026-09-01T10:00:00Z",
      };
      // createdAt DESC, matching findAllByOrderByCreatedAtDesc
      server.cycles = [created, ...server.cycles];
      req.reply({ statusCode: 201, body: created });
    }).as("create");

    cy.intercept("PUT", "/api/admin/exam-cycles/*/activate", (req) => {
      const id = idFrom(req.url);
      // close whatever is open, then open the target — the server's atomic pair
      server.cycles = server.cycles.map((c) => ({ ...c, active: c.id === id }));
      req.reply({ statusCode: 200, body: server.cycles.find((c) => c.id === id) });
    }).as("activate");

    cy.intercept("PUT", "/api/admin/exam-cycles/*/deactivate", (req) => {
      const id = idFrom(req.url);
      server.cycles = server.cycles.map((c) => (c.id === id ? { ...c, active: false } : c));
      req.reply({ statusCode: 200, body: server.cycles.find((c) => c.id === id) });
    }).as("deactivate");
  };

  const JUNE = { id: 1, name: "June 2026 Backlog Exams", examMonthYear: "June 2026", active: false };
  const DEC = { id: 2, name: "December 2025 Backlog Exams", examMonthYear: "December 2025", active: true };

  const visitPage = () => {
    cy.visitAsAdmin("/admin/exam-cycles", { role: "ADMIN", username: "admin" });
    // Explicit: a regressed role guard redirects to /admin, firing unstubbed calls that 401 and
    // sign the session out — surfacing as an unrelated failure lines later.
    cy.location("pathname").should("eq", "/admin/exam-cycles");
    cy.wait("@list");
  };

  // Scoped to the ROW, so an assertion cannot accidentally match another cycle's controls. The
  // cycle list became a <table> when the page was relaid out; "tr" is the same guarantee "li" was.
  const row = (name) => cy.contains("tr", name);

  it("lists cycles, marking exactly the active one", () => {
    stubExamCycles([JUNE, DEC]);
    visitPage();

    row(DEC.name).should("contain", "Active").and("contain", "Accepting registrations");
    row(JUNE.name).should("not.contain", "Active");
    cy.get('[data-cy="cycle-end"]').should("have.length", 1);
    cy.get('[data-cy="cycle-activate"]').should("have.length", 1);
  });

  it("explains an empty list instead of showing a bare panel", () => {
    stubExamCycles([]);
    visitPage();

    cy.contains("No exam cycles yet").should("be.visible");
    cy.get('[data-cy="cycle-activate"]').should("not.exist");
    cy.get('[data-cy="cycle-end"]').should("not.exist");
  });

  it("creates a cycle, trimming both fields, then reloads the list", () => {
    stubExamCycles([]);
    visitPage();

    // Padding is what a paste produces; untrimmed it is stored padded and every later match on
    // the name is off by whitespace.
    cy.get("#cycle-name").type("  June 2026 Backlog Exams  ");
    cy.get("#cycle-my").type("  June 2026  ");
    cy.contains("button", "Create Cycle").click();

    cy.wait("@create").its("request.body").should("deep.equal", {
      name: "June 2026 Backlog Exams",
      examMonthYear: "June 2026",
    });

    // The list is re-fetched, not patched locally — this second @list is the round trip.
    cy.wait("@list");
    row("June 2026 Backlog Exams").should("be.visible");
    // A new cycle is NEVER born active: creating one must not open registration.
    row("June 2026 Backlog Exams").should("not.contain", "Active");
    cy.get('[data-cy="cycle-activate"]').should("have.length", 1);

    // fields cleared, so a second create does not silently resubmit the first name
    cy.get("#cycle-name").should("have.value", "");
    cy.get("#cycle-my").should("have.value", "");
  });

  it("refuses an empty name without calling the server", () => {
    stubExamCycles([]);
    visitPage();

    cy.contains("button", "Create Cycle").click();

    cy.contains("Cycle name is required").should("be.visible");
    // Client check is a convenience copy of ExamCycleRequest's @NotBlank; asserted here is that
    // it short-circuits rather than posting a blank name and rendering the 400.
    cy.get("@create.all").should("have.length", 0);
  });

  it("activating a cycle opens it and closes the one already open", () => {
    stubExamCycles([JUNE, DEC]);
    visitPage();

    row(JUNE.name).find('[data-cy="cycle-activate"]').click();

    cy.wait("@activate").its("request.url").should("include", "/exam-cycles/1/activate");
    cy.wait("@list");

    row(JUNE.name).should("contain", "Active").and("contain", "Accepting registrations");
    // The one that matters: a UI toggling the clicked row locally would leave both badges up.
    row(DEC.name).should("not.contain", "Active");
    cy.get('[data-cy="cycle-end"]').should("have.length", 1);
  });

  it("ending the active cycle closes registration and offers to reopen it", () => {
    stubExamCycles([JUNE, DEC]);
    visitPage();

    row(DEC.name).find('[data-cy="cycle-end"]').click();

    cy.wait("@deactivate").its("request.url").should("include", "/exam-cycles/2/deactivate");
    cy.wait("@list");

    cy.contains("Accepting registrations").should("not.exist");
    cy.get('[data-cy="cycle-end"]').should("not.exist");
    // Both rows now offer Activate — with nothing open, the portal reports registration closed.
    cy.get('[data-cy="cycle-activate"]').should("have.length", 2);
  });

  it("surfaces a failed activate and leaves the switch where it was", () => {
    stubExamCycles([JUNE, DEC]);
    visitPage();

    // Override the happy-path handler; the last matching intercept wins.
    cy.intercept("PUT", "/api/admin/exam-cycles/*/activate", {
      statusCode: 409,
      body: { message: "Another cycle is already active." },
    }).as("activateFails");

    row(JUNE.name).find('[data-cy="cycle-activate"]').click();
    cy.wait("@activateFails");

    cy.contains("Another cycle is already active.").should("be.visible");
    // A failure must not paint the change anyway: December is still the open one.
    row(DEC.name).should("contain", "Active");
    row(JUNE.name).should("not.contain", "Active");
  });
});
