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

    // The edit the server allows only while nothing references the cycle; `referenced` on the row
    // is what the page reads, so the stub must carry it like the real list does.
    cy.intercept("PUT", /\/api\/admin\/exam-cycles\/\d+$/, (req) => {
      const id = Number(req.url.match(/exam-cycles\/(\d+)$/)[1]);
      const target = server.cycles.find((c) => c.id === id);
      if (target.referenced) {
        req.reply({ statusCode: 409, body: { message: "Students have registered under this cycle" } });
        return;
      }
      const updated = { ...target, name: req.body.name, examMonthYear: req.body.examMonthYear,
                        batchLines: req.body.batchLines ?? target.batchLines };
      server.cycles = server.cycles.map((c) => (c.id === id ? updated : c));
      req.reply({ statusCode: 200, body: updated });
    }).as("update");

    cy.intercept("PUT", "/api/admin/exam-cycles/*/deactivate", (req) => {
      const id = idFrom(req.url);
      server.cycles = server.cycles.map((c) => (c.id === id ? { ...c, active: false } : c));
      req.reply({ statusCode: 200, body: server.cycles.find((c) => c.id === id) });
    }).as("deactivate");
  };

  const JUNE = { id: 1, name: "June 2026 Backlog Exams", examMonthYear: "2026-06", active: false };
  const DEC = { id: 2, name: "December 2025 Backlog Exams", examMonthYear: "2025-12", active: true };
  // Created before the format existed. Kept verbatim on screen — no rule recovers a month
  // from free text, and guessing one would invent an exam date.
  const LEGACY = {
    id: 3,
    name: "Testing",
    examMonthYear: "Not a valid month/year",
    active: false,
    batchLines: [{ label: "B.E. I to VII Semester", batch: "2021" }],
  };

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

  it("creates a cycle, trimming the name and composing the month, then reloads the list", () => {
    stubExamCycles([]);
    visitPage();

    // Padding is what a paste produces; untrimmed it is stored padded and every later match on
    // the name is off by whitespace.
    cy.get("#cycle-name").type("  June 2026 Backlog Exams  ");
    cy.get("#cycle-month").select("June");
    cy.get("#cycle-year").type("2026");
    cy.contains("button", "Create Cycle").click();

    // The stored form, composed from the two controls — never the label the admin read.
    cy.wait("@create").its("request.body").should("deep.equal", {
      name: "June 2026 Backlog Exams",
      examMonthYear: "2026-06",
    });

    // The list is re-fetched, not patched locally — this second @list is the round trip.
    cy.wait("@list");
    row("June 2026 Backlog Exams").should("be.visible");
    // A new cycle is NEVER born active: creating one must not open registration.
    row("June 2026 Backlog Exams").should("not.contain", "Active");
    cy.get('[data-cy="cycle-activate"]').should("have.length", 1);

    // fields cleared, so a second create does not silently resubmit the first name
    cy.get("#cycle-name").should("have.value", "");
    cy.get("#cycle-month").should("have.value", "");
    cy.get("#cycle-year").should("have.value", "");
  });

  it("renders the stored YYYY-MM as a month name, and legacy free text verbatim", () => {
    stubExamCycles([JUNE, LEGACY]);
    visitPage();

    row(JUNE.name).should("contain", "June 2026").and("not.contain", "2026-06");
    row(LEGACY.name).should("contain", "Not a valid month/year");
  });

  it("refuses a half-filled or missing month without calling the server", () => {
    stubExamCycles([]);
    visitPage();

    cy.get("#cycle-name").type("Supplementary 2026");
    // Year alone: a month picked from a list can't be malformed, so the half-filled case is the
    // one that matters — composing "2026-" and posting it would 400 on the server's @Pattern.
    cy.get("#cycle-year").type("2026");
    cy.contains("button", "Create Cycle").click();

    cy.contains("Pick the exam month").should("be.visible");
    cy.get("@create.all").should("have.length", 0);
  });

  it("corrects an unreferenced cycle's name and month, then reloads the list", () => {
    stubExamCycles([LEGACY]);
    visitPage();

    row(LEGACY.name).find('[data-cy="cycle-edit"]').click();
    // Legacy free text cannot be split into a month and a year, so the controls start empty
    // rather than guessing a month out of "Not a valid month/year".
    cy.get('[data-cy="cycle-edit-month"]').should("have.value", "");
    cy.get('[data-cy="cycle-edit-name"]').clear().type("June 2026 Backlog Exams");
    cy.get('[data-cy="cycle-edit-month"]').select("June");
    cy.get('[data-cy="cycle-edit-year"]').type("2026");
    cy.get('[data-cy="cycle-edit-save"]').click();

    // The batch list rides along untouched: the whole cycle saves as one block, so an edit that
    // only fixes the name and month must not drop the lines.
    cy.wait("@update").its("request.body").should("deep.equal", {
      name: "June 2026 Backlog Exams",
      examMonthYear: "2026-06",
      batchLines: [{ label: "B.E. I to VII Semester", batch: "2021" }],
    });

    // Re-fetched, not patched locally — the same round trip the create test asserts.
    cy.wait("@list");
    row("June 2026 Backlog Exams").should("contain", "June 2026");
  });

  it("edits the batch list with the rest of the cycle, in one save", () => {
    stubExamCycles([LEGACY]);
    visitPage();

    // The count is what the read-only row shows about the list.
    row(LEGACY.name).find('[data-cy="cycle-batch-count"]').should("contain", "1 batch line");

    row(LEGACY.name).find('[data-cy="cycle-edit"]').click();
    cy.get('[data-cy="cycle-line-add"]').click();
    cy.get('[data-cy="cycle-line-label"]').eq(1).type("M.TECH. I to IV Semester");
    cy.get('[data-cy="cycle-line-batch"]').eq(1).type("2022 & 2023");
    // The legacy month is corrected in the SAME save — the PUT validates it, so a batch-list-only
    // save would 400 on a cycle like this one.
    cy.get('[data-cy="cycle-edit-month"]').select("June");
    cy.get('[data-cy="cycle-edit-year"]').type("2026");
    cy.get('[data-cy="cycle-edit-save"]').click();

    cy.wait("@update").its("request.body.batchLines").should("deep.equal", [
      { label: "B.E. I to VII Semester", batch: "2021" },
      { label: "M.TECH. I to IV Semester", batch: "2022 & 2023" },
    ]);

    cy.wait("@list");
    row(LEGACY.name).find('[data-cy="cycle-batch-count"]').should("contain", "2 batch lines");
  });

  it("refuses a half-filled batch line without calling the server", () => {
    stubExamCycles([LEGACY]);
    visitPage();

    row(LEGACY.name).find('[data-cy="cycle-edit"]').click();
    cy.get('[data-cy="cycle-edit-month"]').select("June");
    cy.get('[data-cy="cycle-edit-year"]').type("2026");
    cy.get('[data-cy="cycle-line-add"]').click();
    // Programme text, no batch: this prints as "... ( Batch Students)" on a signed form.
    cy.get('[data-cy="cycle-line-label"]').eq(1).type("M.TECH. I to IV Semester");
    cy.get('[data-cy="cycle-edit-save"]').click();

    cy.contains("needs both").should("be.visible");
    cy.get("@update.all").should("have.length", 0);
  });

  it("stops the admin adding more lines than the form can hold", () => {
    stubExamCycles([{ ...LEGACY, batchLines: [] }]);
    visitPage();

    row(LEGACY.name).find('[data-cy="cycle-edit"]').click();
    cy.get('[data-cy="cycle-lines-empty"]').should("be.visible");
    for (let i = 0; i < 12; i += 1) cy.get('[data-cy="cycle-line-add"]').click();

    cy.get('[data-cy="cycle-line-label"]').should("have.length", 12);
    cy.get('[data-cy="cycle-line-add"]').should("be.disabled");
  });

  it("offers no edit on a cycle that already has registrations", () => {
    stubExamCycles([{ ...JUNE, referenced: true }]);
    visitPage();

    row(JUNE.name).find('[data-cy="cycle-locked"]').should("contain", "Locked");
    row(JUNE.name).find('[data-cy="cycle-edit"]').should("not.exist");
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
