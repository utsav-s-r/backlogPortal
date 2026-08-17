// The Manage Subjects page: load the catalog, edit a subject (academic year locked, and the code
// prefix with it), and delete — blocked when referenced by registrations, allowed otherwise.
describe("Manage Subjects page", () => {
  const subject = {
    id: 10,
    subjectName: "Data Structures",
    courseCode: "22CSL44",
    semester: 4,
    credits: 4,
    subjectType: "REGULAR",
    academicYearOffered: 2022,
    department: { id: 1, deptName: "Computer Science" },
    eligibleDepartments: [],
  };

  const visitAndLoad = () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science" }],
    }).as("getDepartments");
    // the list endpoint returns a Spring Page envelope, not a bare array
    cy.intercept("GET", "/api/admin/subjects*", {
      statusCode: 200,
      body: { content: [subject], number: 0, totalPages: 1, totalElements: 1 },
    }).as("getSubjects");
    cy.visitAsAdmin("/admin/manage-subjects");
    cy.wait("@getDepartments");
    cy.get('[data-cy="subjects-load"]').click();
    cy.wait("@getSubjects");
    cy.contains("Data Structures").should("be.visible");
  };

  it("edits a subject's credits, keeping the locked course code", () => {
    cy.intercept("PUT", "/api/admin/subjects/10", {
      statusCode: 200,
      body: { ...subject, credits: 3 },
    }).as("updateSubject");

    visitAndLoad();

    cy.get('[data-cy="subject-edit-10"]').click();
    cy.get('[data-cy="subject-credits"]').clear().type("3");
    cy.get('[data-cy="subject-save"]').click();

    cy.wait("@updateSubject")
      .its("request.body")
      .should("deep.equal", {
        subjectName: "Data Structures",
        courseCode: "22CSL44",
        semester: 4,
        credits: 3,
        subjectType: "REGULAR",
        eligibleDeptIds: [],
      });

    cy.contains("3 credits").should("be.visible");
  });

  it("blocks deletion of a subject referenced by registrations", () => {
    cy.intercept("DELETE", "/api/admin/subjects/10", {
      statusCode: 409,
      body: { message: "This subject is referenced by existing registrations and cannot be deleted." },
    }).as("deleteSubject");

    visitAndLoad();
    cy.on("window:confirm", () => true);

    cy.get('[data-cy="subject-delete-10"]').click();
    cy.wait("@deleteSubject");
    cy.get('[data-cy="subject-error-10"]').should("contain", "referenced by existing registrations");
    cy.contains("Data Structures").should("be.visible"); // still there
  });

  it("deletes an unreferenced subject", () => {
    cy.intercept("DELETE", "/api/admin/subjects/10", { statusCode: 204 }).as("deleteSubject");

    visitAndLoad();
    cy.on("window:confirm", () => true);

    cy.get('[data-cy="subject-delete-10"]').click();
    cy.wait("@deleteSubject");
    cy.contains("Data Structures").should("not.exist");
    cy.get('[data-cy="subjects-empty"]').should("be.visible");
  });

  it("switches to the Add and Clone tabs", () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science" }],
    }).as("getDepartments");
    cy.visitAsAdmin("/admin/manage-subjects");
    cy.wait("@getDepartments");

    // defaults to the Manage tab
    cy.get('[data-cy="subjects-load"]').should("be.visible");

    cy.get('[data-cy="tab-add"]').click();
    cy.get("#academicYearOffered").should("be.visible");

    cy.get('[data-cy="tab-clone"]').click();
    cy.get('[data-cy="clone-preview"]').should("be.visible");
  });
});
