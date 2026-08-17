// The Students admin page (tabs: Manage / Add / Import): create, edit, delete (blocked when
// referenced by registrations, allowed otherwise), the per-student semester timeline — the only
// progression surface — and a bulk-import dry-run.
describe("Students page", () => {
  const student = {
    rollNo: "1MS22CS001",
    name: "Asha Rao",
    email: "asha@example.com",
    phone: "9999999999",
    branch: "Computer Science",
    currentSemester: 4,
    entrySemester: 1,
  };

  const stubDepartments = () =>
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS" }],
    }).as("getDepartments");

  const visitManageAndLoad = () => {
    stubDepartments();
    // the list endpoint returns a Spring Page envelope, not a bare array
    cy.intercept("GET", "/api/admin/students*", {
      statusCode: 200,
      body: { content: [student], number: 0, totalPages: 1, totalElements: 1 },
    }).as("getStudents");
    cy.visitAsAdmin("/admin/students");
    cy.wait("@getDepartments");
    cy.get('[data-cy="students-load"]').click();
    cy.wait("@getStudents");
    cy.contains("Asha Rao").should("be.visible");
  };

  it("edits a student's name and semester", () => {
    cy.intercept("PUT", "/api/admin/students/1MS22CS001", {
      statusCode: 200,
      body: { ...student, name: "Asha R", currentSemester: 6 },
    }).as("updateStudent");

    visitManageAndLoad();

    cy.get('[data-cy="student-edit-1MS22CS001"]').click();
    cy.get('[data-cy="student-edit-name"]').clear().type("Asha R");
    cy.get('[data-cy="student-edit-current-sem"]').select("6");
    cy.get('[data-cy="student-save"]').click();

    cy.wait("@updateStudent").its("request.body").should("deep.equal", {
      name: "Asha R",
      phone: "9999999999",
      currentSemester: 6,
      entrySemester: 1,
    });
    cy.contains("Asha R").should("be.visible");
  });

  it("views and edits a student's semester timeline from the Manage tab", () => {
    cy.intercept("GET", "/api/admin/progression/1MS22CS001", {
      statusCode: 200,
      body: {
        rollNo: "1MS22CS001",
        name: "Asha Rao",
        currentSemester: 4,
        entrySemester: 1,
        terms: [
          { semester: 1, academicYear: 2022 },
          { semester: 2, academicYear: 2022 },
          { semester: 3, academicYear: 2023 },
        ],
      },
    }).as("progression");
    cy.intercept("PUT", "/api/admin/progression/1MS22CS001/semester/4", {
      statusCode: 200,
      body: {
        rollNo: "1MS22CS001",
        name: "Asha Rao",
        currentSemester: 4,
        entrySemester: 1,
        terms: [
          { semester: 1, academicYear: 2022 },
          { semester: 2, academicYear: 2022 },
          { semester: 3, academicYear: 2023 },
          { semester: 4, academicYear: 2023 },
        ],
      },
    }).as("setSem4");

    visitManageAndLoad();

    cy.get('[data-cy="student-sems-1MS22CS001"]').click();
    cy.wait("@progression");
    cy.get('[data-cy="student-sems-panel-1MS22CS001"]').should("be.visible");

    // seeded years show in span format; sem 4 (current) is blank + flagged, 5-8 muted
    cy.get('[data-cy="prog-term-year-1"]').should("have.value", "2022-23");
    cy.get('[data-cy="prog-term-year-4"]').should("have.value", "");
    cy.get('[data-cy="prog-term-missing-4"]').should("contain", "not set");
    // the timeline runs to sem 8, not just to the current semester
    cy.get('[data-cy="prog-term-year-8"]').should("exist");

    // fill the blank sem and save -> the PUT override carries the parsed start-year int
    cy.get('[data-cy="prog-term-year-4"]').type("2023-24");
    cy.get('[data-cy="prog-term-save-4"]').click();
    cy.wait("@setSem4").its("request.body").should("deep.equal", { academicYear: 2023 });
    cy.get('[data-cy="prog-term-year-4"]').should("have.value", "2023-24");
  });

  it("blocks deletion of a student referenced by registrations", () => {
    cy.intercept("DELETE", "/api/admin/students/1MS22CS001", {
      statusCode: 409,
      body: { message: "This student has registrations and cannot be deleted." },
    }).as("deleteStudent");

    visitManageAndLoad();
    cy.on("window:confirm", () => true);

    cy.get('[data-cy="student-delete-1MS22CS001"]').click();
    cy.wait("@deleteStudent");
    cy.get('[data-cy="student-error-1MS22CS001"]').should("contain", "cannot be deleted");
    cy.contains("Asha Rao").should("be.visible"); // still there
  });

  it("deletes an unreferenced student", () => {
    cy.intercept("DELETE", "/api/admin/students/1MS22CS001", { statusCode: 204 }).as("deleteStudent");

    visitManageAndLoad();
    cy.on("window:confirm", () => true);

    cy.get('[data-cy="student-delete-1MS22CS001"]').click();
    cy.wait("@deleteStudent");
    cy.contains("Asha Rao").should("not.exist");
    cy.get('[data-cy="students-empty"]').should("be.visible");
  });

  it("creates a student via the Add tab and confirms the seeded timeline", () => {
    cy.intercept("POST", "/api/admin/students", { statusCode: 201, body: { ...student } }).as("createStudent");

    stubDepartments();
    cy.visitAsAdmin("/admin/students?tab=add");
    cy.wait("@getDepartments");

    cy.get('[data-cy="student-usn"]').type("1ms22cs001");
    cy.get('[data-cy="student-name"]').type("Asha Rao");
    cy.get('[data-cy="student-dob"]').type("2004-05-01");
    cy.get('[data-cy="student-current-sem"]').select("4");
    cy.get('[data-cy="student-add-submit"]').click();

    cy.wait("@createStudent").then(({ request }) => {
      expect(request.body.rollNo).to.eq("1MS22CS001"); // upper-cased
      expect(request.body.currentSemester).to.eq(4);
      expect(request.body.entrySemester).to.eq(1);
      expect(request.body.dateOfBirth).to.eq("2004-05-01");
    });
    // the server seeds entry..8 on create, so the banner confirms rather than prompts
    cy.get('[data-cy="student-created-complete"]').should("contain", "full semester timeline seeded");
  });

  it("previews a bulk import (dry-run) on the Import tab", () => {
    cy.intercept("POST", "/api/admin/students/import", {
      statusCode: 200,
      body: {
        dryRun: true,
        created: 1,
        skipped: 0,
        errors: 0,
        results: [{ rollNo: "1MS24CS001", semester: 1, status: "WOULD_CREATE", message: null }],
      },
    }).as("importStudents");

    stubDepartments();
    cy.visitAsAdmin("/admin/students?tab=import");
    cy.wait("@getDepartments");

    cy.get('[data-cy="students-import-csv"]').type(
      "1MS24CS001,Asha Rao,9999999999,2006-04-12,2,1",
    );
    cy.get('[data-cy="students-import-preview"]').click();

    cy.wait("@importStudents").its("request.body").should("deep.include", {
      dryRun: true,
      defaultCurrentSemester: 2,
      defaultEntrySemester: 1,
    });
    cy.get('[data-cy="students-import-result"]').should("contain", "1 created");
  });

  it("switches between the Manage, Add and Import tabs", () => {
    stubDepartments();
    cy.visitAsAdmin("/admin/students");
    cy.wait("@getDepartments");

    cy.get('[data-cy="students-load"]').should("be.visible"); // default Manage tab
    cy.get('[data-cy="tab-add"]').click();
    cy.get('[data-cy="student-usn"]').should("be.visible");
    cy.get('[data-cy="tab-import"]').click();
    cy.get('[data-cy="students-import-csv"]').should("be.visible");
  });
});
