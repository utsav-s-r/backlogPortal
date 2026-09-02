// The Add Subject tab (Manage Subjects ?tab=add). The course code is free text posted verbatim —
// it carries no year information, so the same code recurs across academic years and only
// academicYearOffered distinguishes the offerings.
describe("Add Subject tab", () => {
  const visit = () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science" }],
    }).as("getDepartments");
    cy.visitAsAdmin("/admin/manage-subjects?tab=add");
    cy.wait("@getDepartments");
  };

  const fillAllBut = (omit) => {
    if (omit !== "academicYearOffered") cy.get("#academicYearOffered").select("2022");
    if (omit !== "subjectName") cy.get("#subjectName").type("Data Structures");
    if (omit !== "courseCode") cy.get('[data-cy="course-code"]').type("CSL44");
    if (omit !== "semester") cy.get("#semester").select("4");
    if (omit !== "credits") cy.get("#credits").type("4");
    if (omit !== "deptId") cy.get("#deptId").select("Computer Science");
  };

  // recentAcademicYears is shared with the Import tab. Number("") is 0 and Number.isInteger(0) is
  // true, so an unset picker appended a year 0 whose formatAcademicYear label is "" — a blank,
  // selectable option under the placeholder that the server then 400s on. Asserted on labels, since
  // that is the only way the bad option is visible.
  it("offers no blank year option before one is chosen", () => {
    visit();
    cy.get("#academicYearOffered")
      .find("option")
      .each(($o) => expect($o.text().trim(), "every year option needs a label").to.not.equal(""));
  });

  it("posts the course code verbatim, unmodified by the academic year", () => {
    cy.intercept("POST", "/api/admin/subjects", { statusCode: 201, body: {} }).as("addSubject");
    visit();
    fillAllBut(null);

    cy.contains("button", "Add Subject").click();
    cy.wait("@addSubject").then(({ request }) => {
      // no "22" stamped on the front — the year travels in its own field
      expect(request.body.courseCode).to.eq("CSL44");
      expect(request.body.academicYearOffered).to.eq(2022);
    });
    cy.contains("has been added successfully").should("be.visible");
  });

  // The only client-side guard on the code is the blanket required-fields check; without it an
  // empty code would reach the server, so assert it fires and nothing is posted.
  it("refuses to submit with a blank course code", () => {
    cy.intercept("POST", "/api/admin/subjects", cy.spy().as("addSpy"));
    visit();
    fillAllBut("courseCode");

    cy.contains("button", "Add Subject").click();
    cy.contains("All fields are required.").should("be.visible");
    cy.get("@addSpy").should("not.have.been.called");
  });
});
