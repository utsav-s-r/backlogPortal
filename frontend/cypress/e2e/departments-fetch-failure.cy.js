// The departments fetch used to be swallowed by `.catch(() => {})` in five places. The worst was
// the admin login page: with no departments the <select> has no options, `departmentId` can never
// be set, and the only message shown was "Please select your department." — blaming the user for a
// server-side failure that made HOD/DEPT_OFFICE/PROCTOR unable to sign in at all.
describe("A failed departments fetch is visible, not silent", () => {
  it("admin login: explains the failure instead of demanding an impossible selection", () => {
    cy.intercept("GET", "/api/departments", { statusCode: 500, body: {} }).as("depts");
    cy.visit("/admin/login");
    cy.wait("@depts");

    // pick a department-scoped role to reach the department field
    cy.contains("Head of Department (HOD)").click();

    cy.get('[data-cy="admin-departments-error"]')
      .should("be.visible")
      .and("contain", "Could not load departments");
  });

  it("admin login: a working fetch shows no error and populates the dropdown", () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS" }],
    }).as("depts");
    cy.visit("/admin/login");
    cy.wait("@depts");

    cy.contains("Head of Department (HOD)").click();

    cy.get('[data-cy="admin-departments-error"]').should("not.exist");
    cy.get('[data-cy="admin-department"] option').should("have.length", 2); // placeholder + CSE
  });
});
