// The Import tab (Manage Subjects ?tab=import): bulk-load a year's catalog from CSV.
//
// The academic year and department are chosen ONCE and sent as batch fields, never as CSV columns —
// these assertions are on the request body, because that separation is the whole safeguard against
// a mistyped binding key producing subjects no student can see.
//
// Every /api/admin/** call this page makes is stubbed. An unstubbed one 401s, api.js signs the
// session out, and the spec fails later on an unrelated assertion, intermittently. /api/departments
// is public and answers 200, so it needs no stub for redirect purposes — it is stubbed here only to
// pin the department list the picker renders.
describe("Import Subjects tab", () => {
  const visit = () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [
        { id: 1, deptName: "Computer Science", code: "CS" },
        { id: 2, deptName: "Civil", code: "CV" },
      ],
    }).as("getDepartments");
    cy.visitAsAdmin("/admin/manage-subjects?tab=import");
    cy.wait("@getDepartments");
  };

  const fillYearAndDept = () => {
    cy.get('[data-cy="subjects-import-year"]').select("2025-26");
    cy.get('[data-cy="subjects-import-dept"]').select("Computer Science");
  };

  it("sends the batch year and department, and rows carrying neither", () => {
    cy.intercept("POST", "/api/admin/subjects/import", {
      statusCode: 200,
      body: {
        dryRun: true,
        created: 1,
        skipped: 0,
        errors: 0,
        results: [{ courseCode: "CSL44", semester: 4, status: "WOULD_CREATE", message: null }],
      },
    }).as("importSubjects");

    visit();
    fillYearAndDept();
    cy.get('[data-cy="subjects-import-csv"]').type("CSL44,Data Structures,4,4,REGULAR,");
    cy.get('[data-cy="subjects-import-preview"]').click();

    cy.wait("@importSubjects").its("request.body").should("deep.equal", {
      rows: [
        {
          courseCode: "CSL44",
          subjectName: "Data Structures",
          semester: 4,
          credits: 4,
          subjectType: "REGULAR",
          eligibleDeptCodes: [],
        },
      ],
      deptId: 1,
      academicYearOffered: 2025,
      dryRun: true,
    });

    cy.get('[data-cy="subjects-import-result"]')
      .should("contain", "Preview")
      .and("contain", "CSL44")
      .and("contain", "WOULD_CREATE");
  });

  it("keeps a quoted comma inside the subject name and splits elective codes on |", () => {
    // the silent-corruption case: a naive split would send five cells here and shift credits into
    // subjectType, importing a plausible wrong subject with no error shown anywhere
    cy.intercept("POST", "/api/admin/subjects/import", {
      statusCode: 200,
      body: { dryRun: true, created: 1, skipped: 0, errors: 0, results: [] },
    }).as("importSubjects");

    visit();
    fillYearAndDept();
    cy.get('[data-cy="subjects-import-csv"]').type(
      'CSL55,"Design, Analysis of Algorithms",5,3,ELECTIVE,CS|CV',
      { parseSpecialCharSequences: false },
    );
    cy.get('[data-cy="subjects-import-preview"]').click();

    cy.wait("@importSubjects").its("request.body.rows.0").should("deep.equal", {
      courseCode: "CSL55",
      subjectName: "Design, Analysis of Algorithms",
      semester: 5,
      credits: 3,
      subjectType: "ELECTIVE",
      eligibleDeptCodes: ["CS", "CV"],
    });
  });

  it("sends dryRun false for a real import and reports the counts", () => {
    cy.intercept("POST", "/api/admin/subjects/import", {
      statusCode: 200,
      body: {
        dryRun: false,
        created: 1,
        skipped: 1,
        errors: 0,
        results: [
          { courseCode: "CSL44", semester: 4, status: "CREATED", message: null },
          { courseCode: "CSL45", semester: 4, status: "SKIPPED_EXISTS", message: "Already exists" },
        ],
      },
    }).as("importSubjects");

    visit();
    fillYearAndDept();
    cy.get('[data-cy="subjects-import-csv"]').type("CSL44,Data Structures,4,4,REGULAR,");
    cy.get('[data-cy="subjects-import-apply"]').click();

    cy.wait("@importSubjects").its("request.body.dryRun").should("eq", false);
    cy.get('[data-cy="subjects-import-result"]')
      .should("contain", "Imported")
      .and("contain", "1 created, 1 skipped");
  });

  it("refuses to send without a year, a department, or any rows", () => {
    cy.intercept("POST", "/api/admin/subjects/import", cy.spy().as("importSpy"));
    visit();

    // no year yet
    cy.get('[data-cy="subjects-import-preview"]').click();
    cy.get('[data-cy="subjects-import-error"]').should("contain", "academic year");

    // year but no department
    cy.get('[data-cy="subjects-import-year"]').select("2025-26");
    cy.get('[data-cy="subjects-import-preview"]').click();
    cy.get('[data-cy="subjects-import-error"]').should("contain", "department");

    // both, but an empty textarea
    cy.get('[data-cy="subjects-import-dept"]').select("Computer Science");
    cy.get('[data-cy="subjects-import-preview"]').click();
    cy.get('[data-cy="subjects-import-error"]').should("contain", "Paste at least one row");

    cy.get("@importSpy").should("not.have.been.called");
  });

  it("reports malformed quoting instead of sending a mangled row", () => {
    cy.intercept("POST", "/api/admin/subjects/import", cy.spy().as("importSpy"));
    visit();
    fillYearAndDept();
    cy.get('[data-cy="subjects-import-csv"]').type('CSL44,"unterminated,4,4,REGULAR,', {
      parseSpecialCharSequences: false,
    });
    cy.get('[data-cy="subjects-import-preview"]').click();

    cy.get('[data-cy="subjects-import-error"]').should("contain", "Unclosed quote");
    cy.get("@importSpy").should("not.have.been.called");
  });

  it("pins a dept-scoped admin to their own department with no picker", () => {
    cy.intercept("POST", "/api/admin/subjects/import", {
      statusCode: 200,
      body: { dryRun: true, created: 1, skipped: 0, errors: 0, results: [] },
    }).as("importSubjects");

    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [
        { id: 1, deptName: "Computer Science", code: "CS" },
        { id: 2, deptName: "Civil", code: "CV" },
      ],
    }).as("getDepartments");
    // departmentId matters: findOwnDepartment matches by id first and only falls back to the name
    cy.visitAsAdmin("/admin/manage-subjects?tab=import", {
      role: "HOD",
      username: "hod-user",
      department: "Computer Science",
      departmentId: 1,
    });
    cy.wait("@getDepartments");

    cy.get('[data-cy="subjects-import-dept"]').should("not.exist");
    cy.get('[data-cy="subjects-import-year"]').select("2025-26");
    cy.get('[data-cy="subjects-import-csv"]').type("CSL44,Data Structures,4,4,REGULAR,");
    cy.get('[data-cy="subjects-import-preview"]').click();

    cy.wait("@importSubjects").its("request.body.deptId").should("eq", 1);
  });
});
