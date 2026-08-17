describe("Student registration flow", () => {
  // Identity comes from the authenticated account: the student logs in with USN + date of birth,
  // lands on the dashboard, then registers for subjects.
  const profile = {
    rollNo: "1MS22CS001",
    name: "Student One",
    email: "student1@msrit.edu",
    branch: "Computer Science",
    phone: "9876543210",
    currentSemester: 4,
    // server-derived backlog window for a sem-4 student
    eligibleSemesters: [1, 2, 3, 4],
  };

  function stubAuthedSession({ phone = profile.phone } = {}) {
    cy.intercept("POST", "/api/student/auth/login", {
      statusCode: 200,
      body: { message: "Login success", token: "student-jwt-token", rollNo: profile.rollNo, name: profile.name },
    }).as("studentLogin");

    cy.intercept("GET", "/api/student/me", {
      statusCode: 200,
      body: { ...profile, phone },
    }).as("getProfile");

    cy.intercept("GET", "/api/student/registrations", {
      statusCode: 200,
      body: [],
    }).as("getMyRegistrations");

    cy.intercept("GET", "/api/registration-status", {
      statusCode: 200,
      body: { open: true },
    }).as("getStatus");
  }

  function login() {
    cy.visit("/student/login");
    cy.get('[data-cy="student-usn"]').type(profile.rollNo);
    cy.get('[data-cy="student-dob"]').type("2004-05-15");
    cy.get('[data-cy="student-login-submit"]').click();
    cy.wait("@studentLogin");
    // dashboard loads the profile + the student's own registrations
    cy.wait("@getProfile");
    cy.wait("@getMyRegistrations");
  }

  it("logs in, registers, and shows submission success", () => {
    stubAuthedSession();

    // the year resolves server-side from the student's progression; the client sends only the
    // semester and receives { semester, academicYear, subjects }
    cy.intercept("GET", "/api/student/subjects*", {
      statusCode: 200,
      body: {
        semester: 4,
        academicYear: 2024,
        subjects: [
          { id: 101, subjectName: "Data Structures", academicYearOffered: 2024, department: { deptName: "Computer Science" } },
          { id: 102, subjectName: "Operating Systems", academicYearOffered: 2024, department: { deptName: "Computer Science" } },
        ],
      },
    }).as("getSubjects");

    cy.intercept("POST", "/api/register", {
      statusCode: 200,
      body: { regId: "REG-2026-1001", status: "SUBMITTED" },
    }).as("registerStudent");

    login();

    // dashboard shows the branch name with the USN-derived code in brackets
    cy.contains("Computer Science (CS)").should("be.visible");
    // and the student's current semester
    cy.contains("Current Semester").should("be.visible");
    cy.contains("Semester 4").should("be.visible");

    // go from the dashboard into the registration form
    cy.get('[data-cy="register-cta"]').click();
    cy.location("pathname").should("eq", "/register");

    // identity is shown locked, from the account — no inputs to fill
    cy.contains("Registering as").should("be.visible");
    cy.contains(profile.rollNo).should("be.visible");

    // pick a semester; there is no year input, it resolves from the record
    cy.get('[data-cy="reg-semester"]').select("4");
    cy.wait("@getSubjects");

    // the resolved academic year is shown read-only, in span format
    cy.contains("2024-25").should("be.visible");

    cy.contains("label", "Data Structures").click();
    cy.get('[data-cy="reg-submit"]').click();

    cy.wait("@registerStudent")
      .its("request.body")
      .should((body) => {
        // the body carries only the subject selection, never identity or semester — current
        // semester is snapshotted server-side from the account
        expect(body).to.have.keys(["subjectIds"]);
        expect(body.subjectIds).to.deep.equal([101]);
      });

    cy.contains("Registration Submitted").should("be.visible");
    // the regId is deliberately NOT shown — it is an internal identifier the student cannot use
    cy.contains("REG-2026-1001").should("not.exist");
    // what the student actually needs next
    cy.contains("Download PDF").should("be.visible");
  });

  it("offers only the eligible semesters in the dropdown", () => {
    stubAuthedSession();
    login();
    cy.get('[data-cy="register-cta"]').click();
    cy.location("pathname").should("eq", "/register");

    // a sem-4 student may register sems 1-4 only, never 5-8
    cy.get('[data-cy="reg-semester"] option').then(($opts) => {
      const labels = [...$opts].map((o) => o.textContent.trim());
      expect(labels).to.include("Semester 4");
      expect(labels).to.not.include("Semester 5");
      expect(labels).to.not.include("Semester 8");
    });
  });

  it("shows a clear message when no academic-year record exists (fail closed)", () => {
    stubAuthedSession();
    cy.intercept("GET", "/api/student/subjects*", {
      statusCode: 409,
      body: {
        message:
          "We don't have a record of the academic year you studied semester 3. Please contact the department office.",
      },
    }).as("getSubjectsFailClosed");

    login();
    cy.get('[data-cy="register-cta"]').click();
    cy.get('[data-cy="reg-semester"]').select("3");
    cy.wait("@getSubjectsFailClosed");

    cy.contains("Please contact the department office.").should("be.visible");
  });

  it("blocks registration until a phone number is set", () => {
    stubAuthedSession({ phone: null });

    login();

    cy.get('[data-cy="register-cta"]').click();
    cy.location("pathname").should("eq", "/register");

    // with no phone on the account the form refuses and points to the dashboard
    cy.contains("Add your phone number").should("be.visible");
    cy.get('[data-cy="reg-submit"]').should("not.exist");
  });

  it("redirects unauthenticated visitors to the student login", () => {
    cy.visit("/register");
    cy.location("pathname").should("eq", "/student/login");
    cy.location("search").should("contain", "redirect");
  });
});
