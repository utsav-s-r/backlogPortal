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

  // Guards the ARIA tabs pattern (WCAG 4.1.2): role="tab" without aria-controls, a tabpanel and a
  // roving tabindex announces "tab, 1 of 3" with no route to the panel. Asserts the RELATIONSHIP
  // RESOLVES, not just that the attribute exists — a dangling aria-controls is the same bug
  // wearing a passing test.
  it("wires the tabs to their panel (WCAG 4.1.2) with a roving tabindex", () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science" }],
    }).as("getDepartments");
    cy.visitAsAdmin("/admin/manage-subjects");
    cy.wait("@getDepartments");

    cy.get('[role="tabpanel"]').should("have.length", 1);
    cy.get('[role="tab"]').should("have.length", 3);

    cy.document().then((doc) => {
      const tabs = [...doc.querySelectorAll('[role="tab"]')];
      const panel = doc.querySelector('[role="tabpanel"]');
      tabs.forEach((t) => {
        expect(t.id, "every tab needs an id for aria-labelledby").to.not.equal("");
        // the relationship must RESOLVE — a dangling id reference reads as wired and isn't
        expect(doc.getElementById(t.getAttribute("aria-controls")), `${t.id} -> panel`).to.equal(
          panel,
        );
      });
      expect(doc.getElementById(panel.getAttribute("aria-labelledby")), "panel -> active tab")
        .to.equal(tabs.find((t) => t.getAttribute("aria-selected") === "true"));
      // roving tabindex: exactly one tab stop, and it is the selected tab
      expect(tabs.filter((t) => t.tabIndex === 0).map((t) => t.dataset.cy)).to.deep.equal([
        "tab-manage",
      ]);
    });

    // ArrowRight selects the next tab and takes focus with it (automatic activation)
    cy.get('[data-cy="tab-manage"]').focus().trigger("keydown", { key: "ArrowRight" });
    cy.get('[data-cy="tab-add"]')
      .should("have.attr", "aria-selected", "true")
      .and("have.focus");
    // and the panel's label follows the selection rather than pointing at the old tab
    cy.get('[role="tabpanel"]').should("have.attr", "aria-labelledby", "admin-tab-add");
    // End jumps to the last tab, wrapping rules aside
    cy.get('[data-cy="tab-add"]').trigger("keydown", { key: "End" });
    cy.get('[data-cy="tab-clone"]').should("have.attr", "aria-selected", "true");
  });
});
