// Phone-width regression coverage from the 2026-07 mobile audit. Both bugs it guards were
// page-level horizontal overflow at 375px: a long subject name blowing out the registration list
// (missing min-w-0 on a flex label), and the admin header nav clipping buttons (missing flex-wrap).

const expectNoHorizontalScroll = () =>
  cy.document().its("documentElement").should((el) => {
    expect(el.scrollWidth, "page must not scroll horizontally").to.be.at.most(el.clientWidth);
  });

describe("Mobile viewport (375x812)", () => {
  beforeEach(() => {
    cy.viewport(375, 812);
  });

  it("home page fits the viewport with the header intact", () => {
    // declare the cycle state explicitly, rather than relying on the fetch failing (no backend in
    // e2e) to land in the closed branch
    cy.intercept("GET", "/api/registration-status", { open: false }).as("regStatus");
    cy.visit("/");
    cy.contains("Register for your backlog exam").should("be.visible");
    // the brand logo and the theme toggle share the sticky header row
    cy.get('button[aria-label="Toggle theme"]').should("be.visible");
    expectNoHorizontalScroll();
  });

  it("student registration copes with long subject names", () => {
    cy.intercept("POST", "/api/student/auth/login", {
      statusCode: 200,
      body: { message: "Login success", token: "student-jwt-token", rollNo: "1MS22CS001", name: "Student One" },
    }).as("studentLogin");
    cy.intercept("GET", "/api/student/me", {
      statusCode: 200,
      body: {
        rollNo: "1MS22CS001",
        name: "Student One",
        email: "student1@msrit.edu",
        branch: "Computer Science",
        phone: "9876543210",
        currentSemester: 4,
        eligibleSemesters: [1, 2, 3, 4],
      },
    }).as("getProfile");
    cy.intercept("GET", "/api/student/registrations", { statusCode: 200, body: [] }).as("getMyRegistrations");
    cy.intercept("GET", "/api/registration-status", { statusCode: 200, body: { open: true } });
    cy.intercept("GET", "/api/student/subjects*", {
      statusCode: 200,
      body: {
        semester: 4,
        academicYear: 2024,
        subjects: [
          {
            id: 101,
            subjectName: "Operating Systems Laboratory with Advanced Concepts and Case Studies",
            courseCode: "24CSL46",
            credits: 1,
            academicYearOffered: 2024,
            department: { deptName: "Computer Science" },
          },
        ],
      },
    }).as("getSubjects");

    cy.visit("/student/login");
    cy.get('[data-cy="student-usn"]').type("1MS22CS001");
    cy.get('[data-cy="student-dob"]').type("2004-05-15");
    cy.get('[data-cy="student-login-submit"]').click();
    cy.wait("@studentLogin");
    cy.wait("@getProfile");
    expectNoHorizontalScroll();

    cy.get('[data-cy="register-cta"]').click();
    cy.get('[data-cy="reg-semester"]').select("4");
    cy.wait("@getSubjects");
    cy.contains("24CSL46").should("be.visible");
    // the long name must stay contained instead of widening the page
    expectNoHorizontalScroll();
  });

  it("admin header nav wraps so every button stays reachable", () => {
    cy.intercept("POST", "/api/auth/login", {
      statusCode: 200,
      body: { message: "Login success", role: "ADMIN", token: "admin-jwt-token" },
    }).as("adminLogin");
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [], totalElements: 0, totalPages: 0, number: 0 },
    }).as("getRegistrations");
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 0, submitted: 0, verified: 0, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });

    cy.visit("/admin/login");
    cy.contains("Principal / Registrar / COE").click();
    cy.get('[data-cy="admin-username"]').type("admin");
    cy.get('[data-cy="admin-password"]').type("password123");
    cy.get('[data-cy="admin-login-submit"]').click();
    cy.wait("@adminLogin");
    cy.wait("@getRegistrations");

    // all nav buttons visible — the row wraps instead of clipping
    ["Exam Cycles", "Subjects", "Departments", "Users", "Students", "Logout"].forEach(
      (label) => cy.contains(label).should("be.visible"),
    );
    expectNoHorizontalScroll();
  });
});
