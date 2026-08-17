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
