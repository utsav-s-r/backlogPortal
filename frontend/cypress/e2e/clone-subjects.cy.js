// The Clone Subjects tab (Manage Subjects ?tab=clone): preview generates the bumped draft, the
// grid is editable with removable rows, and apply posts the approved ones. Years are entered in
// span format and sent as start-year ints; WOULD_SKIP rows (already existing) are excluded.
describe("Clone Subjects tab", () => {
  const visit = () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science" }],
    }).as("getDepartments");
    cy.visitAsAdmin("/admin/manage-subjects?tab=clone");
  };

  it("previews, edits, and applies a clone", () => {
    cy.intercept("POST", "/api/admin/subjects/clone/preview", {
      statusCode: 200,
      body: {
        sourceYear: 2024,
        targetYear: 2025,
        deptId: 1,
        rows: [
          { subjectName: "Data Structures", courseCode: "25CSL44", semester: 4, credits: 4, subjectType: "REGULAR", eligibleDeptIds: [], status: "WOULD_CREATE", message: null },
          { subjectName: "Operating Systems", courseCode: "25CSL45", semester: 4, credits: 3, subjectType: "REGULAR", eligibleDeptIds: [], status: "WOULD_SKIP", message: "Already exists for the target year" },
        ],
      },
    }).as("preview");

    cy.intercept("POST", "/api/admin/subjects/clone/apply", {
      statusCode: 200,
      body: {
        created: 1,
        skipped: 0,
        errors: 0,
        rows: [{ courseCode: "25CSL99", semester: 4, status: "CREATED", message: null }],
      },
    }).as("apply");

    visit();
    cy.wait("@getDepartments");

    cy.get('[data-cy="clone-dept"]').select("1");
    cy.get('[data-cy="clone-source-year"]').type("2024-25");
    cy.get('[data-cy="clone-target-year"]').type("2025-26");
    cy.get('[data-cy="clone-preview"]').click();

    // span years parsed to start-year ints; all 8 semesters by default
    cy.wait("@preview")
      .its("request.body")
      .should("deep.equal", {
        deptId: 1,
        sourceYear: 2024,
        targetYear: 2025,
        semesters: [1, 2, 3, 4, 5, 6, 7, 8],
      });

    // one creatable + one already-existing; only the creatable one is applied
    cy.contains("Create 1 subject(s)").should("be.visible");

    // edit the creatable row's code suffix — prefix "25" is locked to the year
    cy.get('[data-cy="clone-row-code-4"]').first().clear().type("CSL99");

    cy.get('[data-cy="clone-apply"]').click();
    cy.wait("@apply")
      .its("request.body")
      .should("deep.equal", {
        deptId: 1,
        targetYear: 2025,
        rows: [
          {
            subjectName: "Data Structures",
            courseCode: "25CSL99",
            semester: 4,
            credits: 4,
            subjectType: "REGULAR",
            eligibleDeptIds: [],
          },
        ],
      });

    cy.get('[data-cy="clone-result"]').should("contain", "1 created");
  });

  // The server explains per-row why a clone was skipped or failed. That reason used to be stored
  // and never rendered, so a failed row read only "Error" and the subject silently went missing
  // from the new year's catalogue.
  it("shows the server's per-row reason in the Detail column", () => {
    cy.intercept("POST", "/api/admin/subjects/clone/preview", {
      statusCode: 200,
      body: {
        sourceYear: 2024,
        targetYear: 2025,
        deptId: 1,
        rows: [
          { subjectName: "Data Structures", courseCode: "25CSL44", semester: 4, credits: 4, subjectType: "REGULAR", eligibleDeptIds: [], status: "WOULD_CREATE", message: null },
          { subjectName: "Compiler Design", courseCode: "25CSL61", semester: 6, credits: 3, subjectType: "REGULAR", eligibleDeptIds: [], status: "WOULD_SKIP", message: "Already exists for the target year" },
        ],
      },
    }).as("preview");

    cy.intercept("POST", "/api/admin/subjects/clone/apply", {
      statusCode: 200,
      body: {
        created: 0,
        skipped: 0,
        errors: 1,
        rows: [{ courseCode: "25CSL44", semester: 4, status: "ERROR", message: "Semester must be between 1 and 8." }],
      },
    }).as("apply");

    visit();
    cy.wait("@getDepartments");

    cy.get('[data-cy="clone-dept"]').select("1");
    cy.get('[data-cy="clone-source-year"]').type("2024-25");
    cy.get('[data-cy="clone-target-year"]').type("2025-26");
    cy.get('[data-cy="clone-preview"]').click();
    cy.wait("@preview");

    // a skip reason is visible straight from the preview
    cy.get('[data-cy="clone-row-detail-6"]').should("contain", "Already exists for the target year");

    cy.get('[data-cy="clone-apply"]').click();
    cy.wait("@apply");

    // the failure reason reaches the admin instead of a bare "Error"
    cy.get('[data-cy="clone-row-detail-4"]').should("contain", "Semester must be between 1 and 8.");
    cy.get('[data-cy="clone-result"]').should("contain", "1 error(s)");
  });

  it("narrows to odd semesters via the shortcut", () => {
    cy.intercept("POST", "/api/admin/subjects/clone/preview", {
      statusCode: 200,
      body: { sourceYear: 2024, targetYear: 2025, deptId: 1, rows: [] },
    }).as("preview");

    visit();
    cy.wait("@getDepartments");

    cy.get('[data-cy="clone-dept"]').select("1");
    cy.get('[data-cy="clone-source-year"]').type("2024");
    cy.get('[data-cy="clone-target-year"]').type("2025");
    cy.contains("button", "Odd").click();
    cy.get('[data-cy="clone-preview"]').click();

    cy.wait("@preview").its("request.body.semesters").should("deep.equal", [1, 3, 5, 7]);
  });
});
