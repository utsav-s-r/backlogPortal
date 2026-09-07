// Phone-width (375px) regression coverage. NOT all about horizontal overflow — four distinct bugs:
//   - a long subject name blowing out the registration list (missing min-w-0 on a flex label)
//   - the admin header nav clipping buttons (missing flex-wrap)
//   - form controls under 16px, which make iOS Safari auto-zoom on focus and never zoom back
//   - the history dialog growing past the viewport, clipped at BOTH ends by `items-center` with no
//     scrollbar (a fixed overlay does not scroll), taking its close button off-screen

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
    // the brand logo and the theme toggle share the sticky header row. The toggle is the WORDS
    // "Dark Mode" next to a switch, so its accessible name is "Dark Mode" — there is no icon to
    // label, which is the whole point of the design.
    cy.get('button[aria-label="Dark Mode"]').should("be.visible");
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

  // Electron can never reproduce the iOS zoom, so the font size IS the contract — assert the
  // computed px, not a visual outcome.
  it("form controls are at least 16px so iOS does not zoom on focus", () => {
    cy.visit("/student/login");
    ["student-usn", "student-dob"].forEach((cy_) =>
      cy.get(`[data-cy="${cy_}"]`).should(($el) => {
        expect(parseFloat(getComputedStyle($el[0]).fontSize), `${cy_} font-size`).to.be.at.least(16);
      }),
    );
  });

  it("history dialog stays inside the viewport when the audit trail is long", () => {
    const row = {
      regId: "REG-2026-1001",
      rollNo: "1MS22CS001",
      studentName: "Student One",
      semester: 4,
      yearOfJoining: 2022,
      subjects: ["Data Structures"],
      status: "VERIFIED",
      verifiedBy: "hod.cse",
      registeredAt: "2026-04-20T10:20:00",
    };
    // every endpoint AdminPage fires on mount — an unstubbed /api/admin call 401s and signs the
    // session out mid-test
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [row], totalElements: 1, totalPages: 1, number: 0 },
    });
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 1, submitted: 0, verified: 1, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
    // 15 events: comfortably past the ~8 that overflowed before the max-h cap
    cy.intercept("GET", `/api/admin/registrations/${row.regId}/events`, {
      statusCode: 200,
      body: Array.from({ length: 15 }, (_, i) => ({
        action: i === 0 ? "SUBMITTED" : "VERIFIED",
        actor: "hod.cse",
        actorRole: "HOD",
        timestamp: "2026-04-20T10:20:00",
        note: `Event ${i + 1}`,
      })),
    }).as("getEvents");

    cy.visitAsAdmin("/admin");
    cy.get(`[data-cy="history-${row.regId}"]`).click();
    cy.wait("@getEvents");

    // `window` in spec scope is the RUNNER's, whose innerHeight is 0 — every `at.most(...)` would
    // then be vacuous. Measure against the app's.
    cy.window().then((win) => {
      cy.get('[data-cy="history-dialog"]').should(($d) => {
        const r = $d[0].getBoundingClientRect();
        expect(r.top, "dialog top is on screen").to.be.at.least(0);
        expect(r.bottom, "dialog bottom is on screen").to.be.at.most(win.innerHeight);
        // the cap is useless without a scroll container to reach the clipped events
        const scroller = $d[0].querySelector(".overflow-y-auto");
        expect(scroller, "content has a scroll container").to.not.be.null;
        expect(scroller.scrollHeight, "content actually scrolls").to.be.greaterThan(
          scroller.clientHeight,
        );
      });
      // the close button was the casualty: it went off the top with the header
      cy.get('[data-cy="history-close"]').should(($b) => {
        const r = $b[0].getBoundingClientRect();
        expect(r.top, "close button is on screen").to.be.at.least(0);
        expect(r.bottom, "close button is on screen").to.be.at.most(win.innerHeight);
      });
    });
    expectNoHorizontalScroll();
  });

  // Admin navigation is a full-screen drawer at this width, not a wrapping row of header pills:
  // closed it must not be reachable at all, opened it must cover the screen and offer every
  // destination. Asserting the drawer is CLOSED first is what keeps this honest — without it the
  // test would pass on a drawer that never opened, since the rail's rows exist either way.
  it("admin nav drawer opens full-screen and offers every destination", () => {
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

    // closed: the rail is display:none below md, so nothing in it is reachable
    cy.contains("a", "Exam cycles").should("not.be.visible");

    cy.get('[data-cy="nav-open"]').click();

    // full-screen means BOTH dimensions — a partial panel with a scrim was explicitly rejected
    cy.get("aside").then(([el]) => {
      const r = el.getBoundingClientRect();
      expect(r.width, "drawer width").to.equal(375);
      expect(r.height, "drawer height").to.equal(812);
      expect(r.top, "drawer top").to.equal(0);
      expect(r.left, "drawer left").to.equal(0);
    });

    // every destination an ADMIN gets, plus the utilities, all reachable in the open drawer
    [
      "Registrations",
      "Students",
      "Subjects",
      "Exam cycles",
      "Departments",
      "Users",
      "My password",
      "Home",
      "Log out",
    ].forEach((label) => cy.contains(label).should("be.visible"));

    expectNoHorizontalScroll();
  });
});
