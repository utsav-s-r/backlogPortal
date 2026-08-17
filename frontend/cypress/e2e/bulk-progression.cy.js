// Bulk progression tab: ADMIN-only visibility, the preview -> typed-confirm -> commit flow, and
// that the confirmed commit echoes the preview's count back as expectedCount (the server's
// double-run guard). The guards themselves are server-side and unit-tested in
// BulkProgressionServiceTest — this spec only proves the UI drives them correctly.
describe("Bulk progression", () => {
  // /admin/students loads departments on mount; leaving it unstubbed 401s and signs the session
  // out mid-test, which fails on an unrelated assertion and only intermittently
  const stubDepartments = () =>
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS" }],
    }).as("getDepartments");

  // the tab loads past runs on mount; leaving this unstubbed 401s and signs the session out
  const stubHistory = (content = []) =>
    cy.intercept("GET", "/api/admin/progression/bulk?*", {
      statusCode: 200,
      body: { content, number: 0, totalPages: 1, totalElements: content.length },
    }).as("getHistory");

  const openBulkTab = (role = "ADMIN") => {
    stubDepartments();
    stubHistory();
    cy.visitAsAdmin("/admin/students?tab=bulk", { role });
    cy.wait("@getDepartments");
  };

  it("is hidden from every role except ADMIN", () => {
    // PRINCIPAL is deliberately excluded too, not just the dept roles
    ["PRINCIPAL", "HOD", "DEPT_OFFICE"].forEach((role) => {
      stubDepartments();
      cy.visitAsAdmin("/admin/students", { role, department: "Computer Science", departmentId: 1 });
      cy.wait("@getDepartments");
      cy.get('[data-cy="tab-bulk"]').should("not.exist");
    });
  });

  it("shows the tab for ADMIN", () => {
    openBulkTab();
    cy.get('[data-cy="tab-bulk"]').should("be.visible");
  });

  it("previews, then commits only after the confirmation word, echoing expectedCount", () => {
    cy.intercept("POST", "/api/admin/progression/bulk/preview", {
      statusCode: 200,
      body: {
        promoteCount: 142,
        // semester-8 students are a COUNT, never rows — in a real run they are thousands
        atMaxCount: 37,
        notPromoted: [
          { rollNo: "1MS24CS007", currentSemester: 4, entrySemester: 1, outcome: "EXCLUDED_BY_ADMIN" },
          { rollNo: "1MS24CS009", currentSemester: 5, entrySemester: 1, outcome: "SKIPPED_INVALID_SEMESTER" },
        ],
      },
    }).as("preview");
    cy.intercept("POST", "/api/admin/progression/bulk", {
      statusCode: 201,
      body: {
        batchId: 3,
        promotedCount: 142,
        excludedCount: 1,
        skippedCount: 1,
      },
    }).as("commit");

    openBulkTab();
    cy.get('[data-cy="bulk-exclusions"]').type("1MS24CS007");
    cy.get('[data-cy="bulk-preview"]').click();
    cy.wait("@preview");

    cy.get('[data-cy="bulk-promote-count"]').should("have.text", "142");
    cy.contains("Held back (your list)").should("be.visible");
    cy.contains("Invalid semester — fix by hand").should("be.visible");
    // at-the-cap students are summarised, not enumerated
    cy.get('[data-cy="bulk-at-max"]').should("contain", "37");

    // the commit button stays disabled until the word matches exactly
    cy.get('[data-cy="bulk-commit"]').should("be.disabled");
    cy.get('[data-cy="bulk-confirm-word"]').type("PROMOT");
    cy.get('[data-cy="bulk-commit"]').should("be.disabled");
    cy.get('[data-cy="bulk-confirm-word"]').type("E");
    cy.get('[data-cy="bulk-commit"]').should("not.be.disabled").click();

    cy.wait("@commit").its("request.body").should((body) => {
      // the count the admin actually saw goes back verbatim — this is what makes a second click
      // 409 rather than promote everyone twice
      expect(body.expectedCount).to.equal(142);
      expect(body.excludeRollNos).to.deep.equal(["1MS24CS007"]);
    });
    cy.get('[data-cy="bulk-result"]').should("contain", "Promoted 142");
  });

  it("editing any input invalidates the preview so a stale count can't be committed", () => {
    cy.intercept("POST", "/api/admin/progression/bulk/preview", {
      statusCode: 200,
      body: { promoteCount: 10, atMaxCount: 0, notPromoted: [] },
    }).as("preview");

    openBulkTab();
    cy.get('[data-cy="bulk-preview"]').click();
    cy.wait("@preview");
    cy.get('[data-cy="bulk-preview-result"]').should("exist");

    cy.get('[data-cy="bulk-exclusions"]').type("1MS24CS001");
    cy.get('[data-cy="bulk-preview-result"]').should("not.exist");
  });

  it("lists past runs and expands one into its held-back rows", () => {
    stubDepartments();
    stubHistory([
      {
        batchId: 3,
        actor: "admin",
        runAt: "2026-08-17T10:00:00Z",
        filterSemester: null,
        filterDeptCode: null,
        promotedCount: 142,
        excludedCount: 1,
        skippedCount: 2,
      },
    ]);
    cy.intercept("GET", "/api/admin/progression/bulk/3", {
      statusCode: 200,
      body: {
        batch: { batchId: 3, promotedCount: 142, excludedCount: 1, skippedCount: 2 },
        notPromoted: [
          { rollNo: "1MS24CS007", semesterFrom: 4, outcome: "EXCLUDED_BY_ADMIN" },
        ],
      },
    }).as("getBatch");

    cy.visitAsAdmin("/admin/students?tab=bulk", { role: "ADMIN" });
    cy.wait("@getDepartments");
    cy.wait("@getHistory");

    cy.get('[data-cy="bulk-history-row-3"]').should("contain", "142 promoted").click();
    cy.wait("@getBatch");
    // the audit is now readable — this is what the batch id in the success banner points at
    cy.get('[data-cy="bulk-history-detail"]').should("contain", "1MS24CS007");
    cy.get('[data-cy="bulk-history-detail"]').should("contain", "Held back (your list)");
  });

  it("reports a history load failure instead of showing an empty list", () => {
    stubDepartments();
    cy.intercept("GET", "/api/admin/progression/bulk?*", { statusCode: 500, body: {} }).as("getHistory");
    cy.visitAsAdmin("/admin/students?tab=bulk", { role: "ADMIN" });
    cy.wait("@getHistory");
    // a swallowed failure would render "No promotions have been run yet", which is a lie
    cy.get('[data-cy="bulk-history-error"]').should("exist");
    cy.get('[data-cy="bulk-history-empty"]').should("not.exist");
  });

  it("surfaces a server refusal in place instead of clearing the session", () => {
    cy.intercept("POST", "/api/admin/progression/bulk/preview", {
      statusCode: 409,
      body: { message: "Close the active exam cycle before promoting students." },
    }).as("preview");

    openBulkTab();
    cy.get('[data-cy="bulk-preview"]').click();
    cy.wait("@preview");
    cy.get('[data-cy="bulk-progression-error"]').should("contain", "Close the active exam cycle");
    cy.location("pathname").should("eq", "/admin/students");
  });
});
