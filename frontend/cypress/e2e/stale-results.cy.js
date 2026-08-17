// A result card describes ONE run. When the next run failed — or never left the browser because
// validation rejected it — the previous card stayed up, so "Preview — 2 created" described a run
// that never happened, sitting beside the error explaining that it didn't. Same shape for a
// filtered list: a failed re-fetch left the previous department's subjects on screen as the new
// department's.
describe("A failed run never leaves the previous result standing", () => {
  const visitImport = () => {
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] }).as("getDepartments");
    cy.visitAsAdmin("/admin/students?tab=import");
    cy.wait("@getDepartments");
  };

  const CSV = "1MS24CS001,Asha Rao,9999999999,2006-04-12,1,1\n1MS24CS002,Bhavya S,,2006-05-02,1,1";

  const PREVIEW_OK = {
    statusCode: 200,
    body: {
      dryRun: true,
      created: 2,
      skipped: 0,
      errors: 0,
      results: [
        { rollNo: "1MS24CS001", semester: 1, status: "WOULD_CREATE", message: "" },
        { rollNo: "1MS24CS002", semester: 1, status: "WOULD_CREATE", message: "" },
      ],
    },
  };

  it("student import: a failed second run clears the first run's card", () => {
    cy.intercept("POST", "/api/admin/students/import", PREVIEW_OK).as("ok");
    visitImport();

    cy.get('[data-cy="students-import-csv"]').type(CSV);
    cy.get('[data-cy="students-import-preview"]').click();
    cy.wait("@ok");
    cy.contains("Preview — 2 created").should("be.visible");

    // second run fails server-side; the first run's card must not survive it
    cy.intercept("POST", "/api/admin/students/import", {
      statusCode: 500,
      body: { message: "Import failed." },
    }).as("boom");
    cy.get('[data-cy="students-import-preview"]').click();
    cy.wait("@boom");

    cy.contains("Import failed.").should("be.visible");
    cy.contains("Preview — 2 created").should("not.exist");
  });

  it("student import: a client-side rejection also clears it (no request is even sent)", () => {
    cy.intercept("POST", "/api/admin/students/import", PREVIEW_OK).as("ok");
    visitImport();

    cy.get('[data-cy="students-import-csv"]').type(CSV);
    cy.get('[data-cy="students-import-preview"]').click();
    cy.wait("@ok");
    cy.contains("Preview — 2 created").should("be.visible");

    // empty the box: the run is rejected before any request, which used to leave the card up
    cy.get('[data-cy="students-import-csv"]').clear();
    cy.get('[data-cy="students-import-preview"]').click();

    cy.contains("Paste at least one row").should("be.visible");
    cy.contains("Preview — 2 created").should("not.exist");
  });

  it("subject list: a failed re-fetch clears the rows instead of relabelling them", () => {
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] }).as("getDepartments");
    cy.intercept("GET", "/api/admin/subjects*", {
      statusCode: 200,
      body: {
        content: [
          {
            id: 1,
            courseCode: "22CSL44",
            subjectName: "Data Structures Lab",
            semester: 4,
            academicYearOffered: 2022,
            credits: 2,
            subjectType: "REGULAR",
          },
        ],
        number: 0,
        totalPages: 1,
        totalElements: 1,
      },
    }).as("subjectsOk");
    cy.visitAsAdmin("/admin/manage-subjects");
    cy.wait("@getDepartments");
    // the list is not fetched on mount — `subjects` starts null ("not loaded yet")
    cy.get('[data-cy="subjects-load"]').click();
    cy.wait("@subjectsOk");
    cy.contains("Data Structures Lab").should("be.visible");

    cy.intercept("GET", "/api/admin/subjects*", { statusCode: 500, body: {} }).as("subjectsBoom");
    cy.get('[data-cy="subjects-load"]').click();
    cy.wait("@subjectsBoom");

    cy.contains("Could not load subjects").should("be.visible");
    cy.contains("Data Structures Lab").should("not.exist");
    // and not the other lie either — [] would claim the filters matched nothing
    cy.get('[data-cy="subjects-empty"]').should("not.exist");
  });
});
