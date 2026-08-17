// Role/department-scoped access on the admin dashboard. The server enforces the real data
// scoping, which stubs can't prove; these lock down the frontend-observable side — department
// badge, the default filter for dept roles, role-gated navigation, PRINCIPAL read-only.
describe("Admin dashboard — role & department scoped access", () => {
  const stubDashboard = (registrations) => {
    // paginated: honor the server-side status filter (dept roles default to SUBMITTED) and
    // return the Spring Page envelope
    cy.intercept("GET", "/api/admin/registrations*", (req) => {
      const status = req.query.status;
      const content = status
        ? registrations.filter((r) => r.status === status)
        : registrations;
      req.reply({
        statusCode: 200,
        body: { content, totalElements: content.length, totalPages: 1, number: 0 },
      });
    }).as("getRegistrations");
    // defined after the list intercept so it wins for the /summary-counts sub-path
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", (req) => {
      const n = (s) => registrations.filter((r) => r.status === s).length;
      req.reply({
        statusCode: 200,
        body: {
          total: registrations.length,
          submitted: n("SUBMITTED"),
          verified: n("VERIFIED"),
          rejected: n("REJECTED"),
        },
      });
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
  };

  it("HOD: shows the department badge, defaults to the pending filter, and hides admin-only nav", () => {
    stubDashboard([
      {
        regId: "REG-S",
        rollNo: "1MS22CS001",
        studentName: "Pending Pam",
        semester: 4,
        subjects: ["Data Structures"],
        status: "SUBMITTED",
        registeredAt: "2026-04-20T10:20:00",
      },
      {
        regId: "REG-V",
        rollNo: "1MS22CS002",
        studentName: "Verified Vic",
        semester: 4,
        subjects: ["Operating Systems"],
        status: "VERIFIED",
        registeredAt: "2026-04-20T10:21:00",
      },
    ]);

    cy.visitAsAdmin("/admin", { role: "HOD", department: "Computer Science" });
    cy.wait("@getRegistrations");

    // department badge in the header
    cy.contains("Computer Science").should("be.visible");

    // dept roles default to the SUBMITTED filter, so only the pending row shows
    cy.contains("td", "Pending Pam").should("exist");
    cy.contains("td", "Verified Vic").should("not.exist");

    // HOD manages users, students (semester timelines are a per-student panel on Manage Students),
    // and their own department's curriculum (add / clone / edit are tabs under Manage Subjects) —
    // not departments
    cy.contains("a", "Users").should("exist");
    cy.contains("a", "Students").should("exist");
    cy.contains("a", "Progression").should("not.exist");
    cy.contains("a", "Subjects").should("exist");
    cy.contains("a", "Add Subject").should("not.exist");
    cy.contains("a", "Clone Subjects").should("not.exist");
    cy.contains("a", "Departments").should("not.exist");
  });

  it("PRINCIPAL: is read-only — a pending row exposes no verify/reject controls", () => {
    stubDashboard([
      {
        regId: "REG-S",
        rollNo: "1MS22CS001",
        studentName: "Pending Pam",
        semester: 4,
        subjects: ["Data Structures"],
        status: "SUBMITTED",
        registeredAt: "2026-04-20T10:20:00",
      },
    ]);

    cy.visitAsAdmin("/admin", { role: "PRINCIPAL" });
    cy.wait("@getRegistrations");

    cy.contains("td", "Pending Pam").should("exist");
    cy.get('[data-cy="admin-verify"]').should("not.exist");
    cy.get('[data-cy="admin-reject"]').should("not.exist");
    cy.contains("Pending Verification").should("exist");
  });

  it("an expired token on a dashboard fetch redirects back to the admin login", () => {
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 401,
      body: { message: "Token expired" },
    }).as("getRegistrations");
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 401,
      body: { message: "Token expired" },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });

    cy.visitAsAdmin("/admin", { role: "ADMIN" });

    cy.location("pathname").should("eq", "/admin/login");
  });
});
