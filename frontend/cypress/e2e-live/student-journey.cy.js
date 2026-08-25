/**
 * The critical path against a REAL backend and database: student login -> register -> PDF.
 *
 * This is the only test in the repo where the browser, the server and Postgres are all real at
 * once. Every other spec `cy.intercept`s its API calls and therefore asserts against a body the
 * spec itself wrote, so a contract drift — a renamed field, a changed status, a session cookie that
 * stops being set — passes the whole suite and breaks only in a browser. Four things are covered
 * here and nowhere else: cookie auth through the vite proxy, the CSRF double-submit, the five
 * server-side preconditions of a registration, and real PDF bytes over a real session.
 *
 * NOT a replacement for the stubbed specs — they exercise error states a real backend will not
 * produce on demand, and they run without any of this. See
 * claude-work/notes/e2e-live-smoke-plan.md.
 *
 * REQUIRES, or every test here fails at the first request:
 *   1. Postgres on :5433 with a `backlog_e2e` database
 *      docker start backlog-test
 *      docker exec backlog-test createdb -U verify backlog_e2e   # first time only
 *   2. The backend booted against THAT database — never the default, which `.env` points at Neon:
 *      cd backend/backlog && ./mvnw spring-boot:run -Dspring-boot.run.arguments="\
 *        --spring.datasource.url=jdbc:postgresql://localhost:5433/backlog_e2e \
 *        --spring.datasource.username=verify --spring.datasource.password=verify \
 *        --app.cors.allowed-origins=http://127.0.0.1:4173 \
 *        --admin.password.admin=e2eSeedAdminPw123"
 *      Both overrides are required. `.env` pins the CORS origins to localhost:5173, so without the
 *      third line the browser's Origin (127.0.0.1:4173, forwarded by the proxy) is refused with
 *      403 "Invalid CORS request" AT LOGIN — while the API-only test below still passes, because
 *      cy.request sends no Origin. That asymmetry is the negative control for this whole spec.
 *   3. npm run dev:e2e   (vite on 4173, proxying /api -> 8080 so the browser sees one origin,
 *      which is what makes SameSite=Lax work)
 * Or just: npm run test:e2e:live, which starts vite and cypress (the backend stays manual).
 */
describe("Student critical path (unstubbed)", () => {
  beforeEach(() => {
    cy.seedRegistrationFixture();
  });

  it("logs in, registers for a backlog subject, and downloads the PDF", function () {
    const { student, subject, cycleName } = this.fixture;

    // ---- login: the real form, a real USN + DOB, a real httpOnly cookie ----
    cy.visit("/student/login");
    cy.get('[data-cy="student-usn"]').type(student.rollNo);
    cy.get('[data-cy="student-dob"]').type(student.dateOfBirth);
    cy.get('[data-cy="student-login-submit"]').click();

    // landing on the dashboard means the cookie survived the proxy hop — the single most likely
    // thing to break here, and invisible to every stubbed spec
    cy.location("pathname").should("eq", "/student");
    cy.contains(student.name).should("be.visible");

    // ---- register ----
    cy.get('[data-cy="register-cta"]').click();
    cy.location("pathname").should("eq", "/register");

    // semester 3 is in the eligibility window {entry..current} = {1..4}; the year binding to
    // AY 2025 is resolved server-side from student_semester_terms, never sent by the client
    cy.get('[data-cy="reg-semester"]').select(String(subject.semester));
    cy.get(`#subject-${subject.id}`).check();
    cy.get('[data-cy="reg-submit"]').click();

    cy.contains("Registration Submitted").should("be.visible");

    // ---- the PDF ----
    // Asserted with cy.request, not by clicking Download: the click hands the file to the browser,
    // which Cypress cannot open. What matters is that a real session renders real bytes.
    cy.request("GET", "/api/student/registrations").then((res) => {
      // Filtered to THIS run's cycle, never asserted as a total: registrations are immutable
      // history, so every previous run's rows are still here and a count assertion would pass
      // exactly once.
      const mine = res.body.filter((r) => r.examCycle === cycleName);
      expect(mine, "this run's registration reached the database").to.have.length(1);
      const registration = mine[0];
      // snapSemester is the student's CURRENT semester (4), NOT the backlog semester being
      // registered (3) — taken server-side. Trusting a client value here printed the wrong
      // semester on every form, so it is worth pinning in the one test that sees the real value.
      expect(registration.semester, "snapshot semester is the student's current one").to.eq(
        student.currentSemester
      );
      expect(registration.status).to.eq("SUBMITTED");
      expect(registration.subjects.join()).to.contain(subject.courseCode);

      cy.request({
        url: `/api/student/registrations/${registration.regId}/pdf`,
        encoding: "binary",
      }).then((pdf) => {
        expect(pdf.status).to.eq(200);
        expect(pdf.headers["content-type"]).to.contain("application/pdf");
        expect(pdf.body.length, "a real rendered PDF, not an empty body").to.be.greaterThan(1000);
        expect(pdf.body.slice(0, 5)).to.eq("%PDF-");
      });
    });
  });

  it("refuses a second submission for the same cycle", function () {
    // The pending-per-cycle cap is server-side and has no UI path — a stubbed spec could only
    // assert the message it invented. Driven by API because the browser never offers this.
    const { student, subject } = this.fixture;
    cy.loginAsStudent(student.rollNo, student.dateOfBirth);

    cy.apiWrite("POST", "/api/register", { subjectIds: [subject.id] })
      .its("status")
      .should("eq", 200);

    cy.apiWrite("POST", "/api/register", { subjectIds: [subject.id] }, {
      failOnStatusCode: false,
    }).then((res) => {
      // 409, not 400: the submission is well-formed — it is the account state that refuses it
      expect(res.status).to.eq(409);
    });
  });
});
