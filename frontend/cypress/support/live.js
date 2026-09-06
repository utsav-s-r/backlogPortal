// Support file for the UNSTUBBED smoke specs (cypress/e2e-live), loaded only by
// cypress.live.config.js. Deliberately does NOT install the default support file's
// unstubbed-admin-call guard: there, a real /api/admin call means a missing intercept; here it is
// the entire point.
//
// Everything below seeds through the REAL admin API rather than SQL, so the domain rules have one
// definition and `createStudent` seeds the progression timeline (seedLinearTimeline) for free.
// Preconditions, traps and the boot command: the header of e2e-live/student-journey.cy.js.

beforeEach(() => {
  cy.clearLocalStorage();
  cy.clearAllSessionStorage();
  cy.clearCookies();
});

/** The seeded bootstrap admin. Must match --admin.password.admin on the backend boot command. */
const SEED_ADMIN = { username: "admin", password: "e2eSeedAdminPw123" };

/**
 * Every mutation needs the CSRF double-submit: the XSRF-TOKEN cookie echoed as the X-XSRF-TOKEN
 * header. Login and logout are exempt (SecurityConfig), which is why they call cy.request directly.
 * Wrapped because forgetting it yields a 401 that reads exactly like an expired session.
 */
Cypress.Commands.add("apiWrite", (method, url, body, options = {}) => {
  return cy.getCookie("XSRF-TOKEN").then((token) =>
    cy.request({
      method,
      url,
      body,
      headers: { "X-XSRF-TOKEN": token ? token.value : "" },
      // callers that tolerate a 409 (already seeded) pass failOnStatusCode: false
      ...options,
    })
  );
});

Cypress.Commands.add("loginAsSeedAdmin", () => {
  cy.request("POST", "/api/auth/login", SEED_ADMIN).its("status").should("eq", 200);
});

Cypress.Commands.add("loginAsStudent", (rollNo, dateOfBirth) => {
  cy.request("POST", "/api/student/auth/login", { rollNo, dateOfBirth })
    .its("status")
    .should("eq", 200);
});

/**
 * Seed the whole precondition set for one registration and return what the spec needs.
 *
 * Idempotent by construction rather than by cleanup: department, student and subject tolerate a 409
 * (a rerun finds them already there), while the EXAM CYCLE is always new. That last part is what
 * makes reruns work at all — the pending-registration cap and the uq_pending_reg_per_cycle partial
 * unique index are both per (student, cycle), so a fresh cycle sidesteps both without deleting
 * anything.
 */
Cypress.Commands.add("seedRegistrationFixture", () => {
  const student = {
    rollNo: "1MS24CS001",
    name: "E2E Student",
    dateOfBirth: "2006-01-01",
    phone: "9876543210",
    currentSemester: 4,
    entrySemester: 1,
  };
  // Sem 3 of a 2024 intake entering at sem 1 falls in AY 2025 (seedLinearTimeline: 1-2 -> 2024,
  // 3-4 -> 2025). The subject's academicYearOffered must match that year exactly — year-binding is
  // server-enforced and fails closed, so a mismatch here makes the student ineligible. The course
  // code is free text; nothing derives a year from it.
  const subject = {
    subjectName: "Data Structures",
    courseCode: "25CS31",
    semester: 3,
    credits: 4,
    academicYearOffered: 2025,
  };
  const tolerateConflict = { failOnStatusCode: false };

  cy.loginAsSeedAdmin();

  cy.apiWrite("POST", "/api/admin/departments", {
    deptName: "Computer Science and Engineering",
    code: "CS",
    contactEmail: "cse@msrit.edu",
  }, tolerateConflict);

  // resolve the department id whether this run created it or a previous one did
  cy.request("GET", "/api/departments").then((res) => {
    const dept = res.body.find((d) => d.code === "CS");
    expect(dept, "CS department").to.exist;

    cy.apiWrite("POST", "/api/admin/students", {
      rollNo: student.rollNo,
      name: student.name,
      dateOfBirth: student.dateOfBirth,
      currentSemester: student.currentSemester,
      entrySemester: student.entrySemester,
    }, tolerateConflict);

    // Phone is NOT part of create and a registration 409s without a 10-digit one, so it is set
    // here rather than assumed. Also re-applies on a rerun, which costs nothing.
    cy.apiWrite("PUT", `/api/admin/students/${student.rollNo}`, {
      name: student.name,
      phone: student.phone,
      currentSemester: student.currentSemester,
      entrySemester: student.entrySemester,
    });

    cy.apiWrite("POST", "/api/admin/subjects", {
      ...subject,
      deptId: dept.id,
    }, tolerateConflict);

    cy.request("GET", "/api/admin/subjects?size=200").then((subjects) => {
      const seeded = subjects.body.content.find((s) => s.courseCode === subject.courseCode);
      expect(seeded, "seeded subject").to.exist;

      // always a NEW cycle — see the command javadoc. Its name is returned so the spec can pick
      // out THIS run's registration: registrations are immutable history, so the student keeps
      // every earlier run's rows and a total-count assertion would pass once and never again.
      const cycleName = `E2E ${Date.now()}`;
      cy.apiWrite("POST", "/api/admin/exam-cycles", {
        name: cycleName,
        examMonthYear: "2026-06",
      }).then((cycle) => {
        cy.apiWrite("PUT", `/api/admin/exam-cycles/${cycle.body.id}/activate`);
        // clear the admin session: the spec logs in as the student next, and leaving both cookies
        // set is not a state any real browser reaches
        cy.request("POST", "/api/auth/logout");
        cy.clearCookies();
        cy.wrap({ student, subject: seeded, cycleName }).as("fixture");
      });
    });
  });
});
