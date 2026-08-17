// Registration edge cases the happy-path spec doesn't cover: a closed exam cycle, a
// duplicate-submission conflict, an expired session. Each lands on /register with a seeded student
// token to focus on the page.
describe("Student registration — closed cycle, duplicate, expired token", () => {
  const profile = {
    rollNo: "1MS22CS001",
    name: "Student One",
    email: "student1@msrit.edu",
    branch: "Computer Science",
    phone: "9876543210",
    currentSemester: 4,
    eligibleSemesters: [1, 2, 3, 4],
  };

  it("shows the No Open Registrations card when no cycle is open", () => {
    cy.intercept("GET", "/api/student/me", { statusCode: 200, body: profile });
    cy.intercept("GET", "/api/registration-status", {
      statusCode: 200,
      body: { open: false },
    }).as("getStatus");

    cy.visitAsStudent("/register", { rollNo: profile.rollNo, name: profile.name });
    cy.wait("@getStatus");

    cy.contains("No Open Registrations").should("be.visible");
    cy.get('[data-cy="reg-submit"]').should("not.exist");
  });

  it("surfaces the server message when a duplicate submission is rejected (409)", () => {
    cy.intercept("GET", "/api/student/me", { statusCode: 200, body: profile });
    cy.intercept("GET", "/api/registration-status", {
      statusCode: 200,
      body: { open: true },
    });
    cy.intercept("GET", "/api/student/subjects*", {
      statusCode: 200,
      body: {
        semester: 4,
        academicYear: 2024,
        subjects: [
          {
            id: 101,
            subjectName: "Data Structures",
            academicYearOffered: 2024,
            department: { deptName: "Computer Science" },
          },
        ],
      },
    }).as("getSubjects");
    cy.intercept("POST", "/api/register", {
      statusCode: 409,
      body: {
        message: "You already have a pending registration for this exam cycle.",
      },
    }).as("register");

    cy.visitAsStudent("/register", { rollNo: profile.rollNo, name: profile.name });

    cy.get('[data-cy="reg-semester"]').select("4");
    cy.wait("@getSubjects");
    cy.contains("label", "Data Structures").click();
    cy.get('[data-cy="reg-submit"]').click();

    cy.wait("@register");
    cy.contains("You already have a pending registration").should("be.visible");
    // still on the form, not the submitted-success screen
    cy.contains("Registration Submitted").should("not.exist");
  });

  it("an expired token redirects to the student login", () => {
    cy.intercept("GET", "/api/student/me", {
      statusCode: 401,
      body: { message: "Token expired" },
    }).as("getProfile");
    cy.intercept("GET", "/api/registration-status", {
      statusCode: 200,
      body: { open: true },
    });

    cy.visitAsStudent("/register", { rollNo: profile.rollNo, name: profile.name });

    cy.location("pathname").should("eq", "/student/login");
  });
});
