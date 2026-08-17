// The Add Subject tab (Manage Subjects ?tab=add). The academic year is authoritative: it stamps
// the course code's locked two-digit prefix and the admin types only the suffix; the composed code
// is what gets posted.
describe("Add Subject tab", () => {
  const visit = () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science" }],
    }).as("getDepartments");
    cy.visitAsAdmin("/admin/manage-subjects?tab=add");
    cy.wait("@getDepartments");
  };

  it("locks the course code until an academic year is chosen", () => {
    visit();
    cy.get('[data-cy="course-code-suffix"]').should("be.disabled");
    cy.get("#academicYearOffered").select("2022");
    cy.get('[data-cy="course-code-suffix"]').should("not.be.disabled");
  });

  it("composes the course code from the chosen academic year + suffix", () => {
    cy.intercept("POST", "/api/admin/subjects", { statusCode: 201, body: {} }).as("addSubject");
    visit();

    cy.get("#academicYearOffered").select("2022"); // stamps the "22" prefix
    cy.get("#subjectName").type("Data Structures");
    cy.get('[data-cy="course-code-suffix"]').type("CSL44");
    cy.get("#semester").select("4");
    cy.get("#credits").type("4");
    cy.get("#deptId").select("Computer Science");

    cy.contains("button", "Add Subject").click();
    cy.wait("@addSubject").then(({ request }) => {
      expect(request.body.courseCode).to.eq("22CSL44");
      expect(request.body.academicYearOffered).to.eq(2022);
    });
    cy.contains("has been added successfully").should("be.visible");
  });
});
