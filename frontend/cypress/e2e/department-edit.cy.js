describe("Department code edit — concurrent-edit conflict detection", () => {
  // Drop onto the page with an admin session in place, so the test exercises the save/conflict
  // path rather than re-driving the login UI.
  const authedVisit = (path) => cy.visitAsAdmin(path);

  it("resyncs to the server value when a code save hits a 409 conflict", () => {
    // First load is "CS" at version 0; the conflicting PUT flips the flag, so the automatic
    // resync returns what another admin already saved ("CX" at version 1). Keyed off the PUT
    // rather than a call counter, so it survives however many fetches the page makes (StrictMode).
    let conflictHit = false;
    cy.intercept("GET", "/api/admin/departments*", (req) => {
      req.reply({
        statusCode: 200,
        body: [
          {
            id: 1,
            deptName: "Computer Science",
            code: conflictHit ? "CX" : "CS",
            contactEmail: null,
            version: conflictHit ? 1 : 0,
          },
        ],
      });
    }).as("getDepartments");

    cy.intercept("PUT", "/api/admin/departments/1", (req) => {
      conflictHit = true;
      req.reply({
        statusCode: 409,
        body: {
          message:
            "This department was changed by someone else. Reload and try again.",
        },
      });
    }).as("updateConflict");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments"); // initial load (CS, v0)

    // edit the code and save -> server rejects the stale write with 409
    cy.get('[data-cy="dept-code-input-1"]').clear().type("EC");
    cy.get('[data-cy="dept-save-1"]').click();

    cy.wait("@updateConflict");
    cy.wait("@getDepartments"); // automatic resync after the 409

    // conflict surfaced inline, field resynced to the other admin's value
    cy.get('[data-cy="dept-error"]').should("contain", "changed by someone else");
    cy.get('[data-cy="dept-code-input-1"]').should("have.value", "CX");
  });

  // The code is the branch segment of every student's USN (1MS24CS001) and decides which
  // department may verify their registrations, so it is fixed once students exist. The server
  // 409s the change; the lock is what stops the admin typing one and losing the save.
  it("locks the code of a department that has students, leaving the rest editable", () => {
    cy.intercept("GET", "/api/admin/departments*", {
      statusCode: 200,
      body: [
        { id: 1, deptName: "Computer Science", code: "CS", contactEmail: null, version: 0,
          hasStudents: true, hasVerifier: true },
        { id: 2, deptName: "Aeronautical", code: "AE", contactEmail: null, version: 0,
          hasStudents: false, hasVerifier: false },
      ],
    }).as("getDepartments");
    cy.intercept("PUT", "/api/admin/departments/1", { statusCode: 200, body: {} }).as("update");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments");

    cy.get('[data-cy="dept-code-input-1"]').should("be.disabled");
    cy.get('[data-cy="dept-code-locked-1"]').should("contain", "USNs carry it");
    // The control: a department nobody has been admitted into is still correctable, so this
    // cannot pass on a page that disabled every code field.
    cy.get('[data-cy="dept-code-input-2"]').should("not.be.disabled");
    cy.get('[data-cy="dept-code-locked-2"]').should("not.exist");

    // The rest of the row still saves, code unchanged — the guard must not freeze the whole row.
    cy.get('[data-cy="dept-email-input-1"]').clear().type("cse@msrit.edu");
    cy.get('[data-cy="dept-save-1"]').click();
    cy.wait("@update").its("request.body").should("include", { code: "CS", contactEmail: "cse@msrit.edu" });
  });

  // Only the student's own department verifies their registrations, so a department holding
  // students with no HOD or office has a queue nobody is watching — an admin can still act but
  // has no reason to know they need to. Reported, never enforced: any setup order stays legal.
  it("warns about a department that has students but nobody who can verify them", () => {
    cy.intercept("GET", "/api/admin/departments*", {
      statusCode: 200,
      body: [
        { id: 1, deptName: "Civil", code: "CV", contactEmail: null, version: 0,
          hasStudents: true, hasVerifier: false },
        // the two controls: the warning is the PAIR, so neither half alone may trigger it
        { id: 2, deptName: "Computer Science", code: "CS", contactEmail: null, version: 0,
          hasStudents: true, hasVerifier: true },
        { id: 3, deptName: "Aeronautical", code: "AE", contactEmail: null, version: 0,
          hasStudents: false, hasVerifier: false },
      ],
    }).as("getDepartments");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments");

    cy.get('[data-cy="dept-no-verifier-1"]').should("contain", "No HOD or department office");
    cy.get('[data-cy="dept-no-verifier-2"]').should("not.exist");
    // staffless but empty is an ordinary half-built department, not a problem
    cy.get('[data-cy="dept-no-verifier-3"]').should("not.exist");
    // and it warns without blocking anything — the row stays fully editable
    cy.get('[data-cy="dept-name-input-1"]').should("not.be.disabled");
    cy.get('[data-cy="dept-email-input-1"]').should("not.be.disabled");
  });

  it("saves an edited contact email through the inline row", () => {
    cy.intercept("GET", "/api/admin/departments*", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS", contactEmail: null, version: 0 }],
    }).as("getDepartments");

    cy.intercept("PUT", "/api/admin/departments/1", (req) => {
      // the edited email is what reaches the server, code preserved
      expect(req.body).to.include({ code: "CS", contactEmail: "cse@msrit.edu" });
      req.reply({ statusCode: 200, body: { id: 1, deptName: "Computer Science", code: "CS", contactEmail: "cse@msrit.edu", version: 1 } });
    }).as("updateDept");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments");

    // Save is disabled until something changes; typing an email enables it
    cy.get('[data-cy="dept-save-1"]').should("be.disabled");
    cy.get('[data-cy="dept-email-input-1"]').type("cse@msrit.edu");
    cy.get('[data-cy="dept-save-1"]').should("not.be.disabled").click();

    cy.wait("@updateDept");
    cy.get('[data-cy="dept-error"]').should("not.exist");
  });

  it("saves an edited department name through the inline row", () => {
    cy.intercept("GET", "/api/admin/departments*", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS", contactEmail: null, version: 0 }],
    }).as("getDepartments");

    cy.intercept("PUT", "/api/admin/departments/1", (req) => {
      // the rename reaches the server; the code (identity) is untouched
      expect(req.body).to.include({ deptName: "Computer Science & Engineering", code: "CS" });
      req.reply({
        statusCode: 200,
        body: { id: 1, deptName: "Computer Science & Engineering", code: "CS", contactEmail: null, version: 1 },
      });
    }).as("updateDept");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments");

    // Save is disabled until something changes; editing the name enables it
    cy.get('[data-cy="dept-save-1"]').should("be.disabled");
    cy.get('[data-cy="dept-name-input-1"]').clear().type("Computer Science & Engineering");
    cy.get('[data-cy="dept-save-1"]').should("not.be.disabled").click();

    cy.wait("@updateDept");
    cy.get('[data-cy="dept-error"]').should("not.exist");
  });

  it("deletes an unreferenced department after a two-step confirm", () => {
    let deleted = false;
    cy.intercept("GET", "/api/admin/departments*", (req) => {
      req.reply({
        statusCode: 200,
        body: deleted
          ? []
          : [{ id: 1, deptName: "Computer Science", code: "CS", contactEmail: null, version: 0 }],
      });
    }).as("getDepartments");

    cy.intercept("DELETE", "/api/admin/departments/1", (req) => {
      deleted = true;
      req.reply({ statusCode: 204 });
    }).as("deleteDept");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments");

    // first click only arms the confirm — the DELETE fires on the second
    cy.get('[data-cy="dept-delete-1"]').click();
    cy.get('[data-cy="dept-delete-confirm-1"]').click();

    cy.wait("@deleteDept");
    cy.wait("@getDepartments"); // reload after delete
    cy.contains("No departments yet").should("be.visible");
  });

  it("surfaces the 409 reason when deleting a referenced department", () => {
    cy.intercept("GET", "/api/admin/departments*", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS", contactEmail: null, version: 0 }],
    }).as("getDepartments");

    cy.intercept("DELETE", "/api/admin/departments/1", {
      statusCode: 409,
      body: { message: "This department is referenced by existing subjects and cannot be deleted." },
    }).as("deleteBlocked");

    authedVisit("/admin/departments");
    cy.wait("@getDepartments");

    cy.get('[data-cy="dept-delete-1"]').click();
    cy.get('[data-cy="dept-delete-confirm-1"]').click();

    cy.wait("@deleteBlocked");
    cy.get('[data-cy="dept-error"]').should("contain", "referenced by existing subjects");
    // row still present since the delete was rejected; the confirm stays armed
    cy.get('[data-cy="dept-delete-confirm-1"]').should("exist");
  });
});
