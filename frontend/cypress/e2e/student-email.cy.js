// The student's own email edit on the dashboard. Typed twice as the only typo guard; "Use college
// email" fills both with <usn>@msrit.edu. The server (Emails.java) is the control.
describe("Student dashboard — email edit", () => {
  const profile = {
    rollNo: "1MS22CS001",
    name: "Student One",
    email: "1ms22cs001@msrit.edu",
    branch: "Computer Science",
    phone: "9876543210",
    currentSemester: 4,
    eligibleSemesters: [1, 2, 3, 4],
  };

  const visitDashboard = () => {
    cy.intercept("GET", "/api/student/me", { statusCode: 200, body: profile });
    cy.intercept("GET", "/api/student/registrations", { statusCode: 200, body: [] }).as("getRegs");
    cy.visitAsStudent("/student", { rollNo: profile.rollNo, name: profile.name });
    cy.wait("@getRegs");
  };

  it("saves a new address typed twice and shows it", () => {
    cy.intercept("PUT", "/api/student/me/email", (req) => {
      req.reply({ statusCode: 200, body: { ...profile, email: req.body.email } });
    }).as("putEmail");
    visitDashboard();

    cy.get('[data-cy="email-value"]').should("contain", "1ms22cs001@msrit.edu");
    cy.get('[data-cy="email-edit"]').click();
    cy.get('[data-cy="email-input"]').type("  student.one@gmail.com ");
    cy.get('[data-cy="email-confirm"]').type("student.one@gmail.com");
    cy.get('[data-cy="email-save"]').click();

    cy.wait("@putEmail").its("request.body").should("deep.equal", { email: "student.one@gmail.com" });
    cy.get('[data-cy="email-value"]').should("contain", "student.one@gmail.com");
    cy.get('[data-cy="email-input"]').should("not.exist");
  });

  it("refuses a mismatch or a malformed address without sending", () => {
    cy.intercept("PUT", "/api/student/me/email", cy.spy().as("putSpy"));
    visitDashboard();

    cy.get('[data-cy="email-edit"]').click();
    cy.get('[data-cy="email-input"]').type("student.one@gmail.com");
    cy.get('[data-cy="email-confirm"]').type("student.one@gmial.com");
    cy.get('[data-cy="email-save"]').click();
    cy.get('[data-cy="email-error"]').should("contain", "don't match");

    cy.get('[data-cy="email-input"]').clear().type("student.one@gmail");
    cy.get('[data-cy="email-confirm"]').clear().type("student.one@gmail");
    cy.get('[data-cy="email-save"]').click();
    cy.get('[data-cy="email-error"]').should("contain", "valid email");

    cy.get("@putSpy").should("not.have.been.called");

    // cancelling drops the error with the form
    cy.contains("button", "Cancel").click();
    cy.get('[data-cy="email-error"]').should("not.exist");
  });

  it("'Use college email' fills both fields with the USN address", () => {
    cy.intercept("PUT", "/api/student/me/email", {
      statusCode: 200,
      body: profile,
    }).as("putEmail");
    visitDashboard();

    cy.get('[data-cy="email-edit"]').click();
    cy.get('[data-cy="email-use-college"]').click();
    cy.get('[data-cy="email-input"]').should("have.value", "1ms22cs001@msrit.edu");
    cy.get('[data-cy="email-confirm"]').should("have.value", "1ms22cs001@msrit.edu");
    cy.get('[data-cy="email-save"]').click();
    cy.wait("@putEmail").its("request.body.email").should("equal", "1ms22cs001@msrit.edu");
  });

  it("shows the server's reason when the save is refused", () => {
    cy.intercept("PUT", "/api/student/me/email", {
      statusCode: 400,
      body: { message: "Enter a valid email address." },
    }).as("putEmail");
    visitDashboard();

    cy.get('[data-cy="email-edit"]').click();
    cy.get('[data-cy="email-input"]').type("a@b.co");
    cy.get('[data-cy="email-confirm"]').type("a@b.co");
    cy.get('[data-cy="email-save"]').click();
    cy.wait("@putEmail");
    cy.get('[data-cy="email-error"]').should("contain", "Enter a valid email address.");
    cy.get('[data-cy="email-input"]').should("exist");
  });

  // Phone shares the edit shape and had the same bug: an error outlived the cancelled form.
  it("cancelling the phone edit drops its error too", () => {
    cy.intercept("PUT", "/api/student/me/phone", cy.spy().as("putPhone"));
    visitDashboard();

    cy.get('[data-cy="phone-edit"]').click();
    cy.get('[data-cy="phone-input"]').clear().type("12345");
    cy.get('[data-cy="phone-save"]').click();
    cy.contains("exactly 10 digits").should("be.visible");
    cy.contains("button", "Cancel").click();
    cy.contains("exactly 10 digits").should("not.exist");
    cy.get("@putPhone").should("not.have.been.called");
  });
});
