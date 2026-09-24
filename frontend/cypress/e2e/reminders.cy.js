// The Reminders page (ADMIN). Every endpoint it loads is stubbed — an unstubbed /api/admin call
// 401s against the proxy and signs the session out. The server is the control for every rule
// here (ReminderService); these pin what the page sends and shows.
describe("Reminders — scheduled student emails", () => {
  let server;

  const cycles = [
    { id: 3, name: "June 2026 Cycle", examMonthYear: "2026-06", active: true },
    { id: 4, name: "Dec 2026 Cycle", examMonthYear: "2026-12", active: false },
  ];
  const departments = [
    { id: 1, deptName: "Computer Science & Engineering (CSE)", code: "CS" },
    { id: 19, deptName: "Civil Engineering", code: "CV" },
  ];

  const reminder = (over) => ({
    id: 10,
    examCycleId: 3,
    examCycleName: "June 2026 Cycle",
    departmentCode: null,
    subject: "Hall tickets",
    message: "Collect them.",
    sendAt: "2026-10-05T03:30:00Z", // 09:00 IST
    status: "SCHEDULED",
    sent: 0,
    failed: 0,
    lastError: null,
    ...over,
  });

  const stub = ({ list = [], config = {} } = {}) => {
    server = { list };
    cy.intercept("GET", "/api/admin/reminders/config", {
      statusCode: 200,
      body: {
        mailConfigured: true,
        senderEmail: "portal@gmail.com",
        senderName: "MSRIT Backlog Portal",
        triggerConfigured: true,
        dailyCap: 280,
        ...config,
      },
    }).as("config");
    cy.intercept("GET", "/api/admin/reminders", (req) => req.reply({ statusCode: 200, body: server.list })).as("list");
    cy.intercept("GET", "/api/admin/exam-cycles", { statusCode: 200, body: cycles });
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: departments });
  };

  const visit = () => {
    cy.visitAsAdmin("/admin/reminders");
    cy.wait(["@config", "@list"]);
  };

  const fillForm = () => {
    cy.get('[data-cy="reminder-cycle"]').select("June 2026 Cycle (active)");
    cy.get('[data-cy="reminder-date"]').type("2026-10-05");
    cy.get('[data-cy="reminder-time"]').clear().type("09:00");
    cy.get('[data-cy="reminder-subject"]').type("Hall tickets");
    cy.get('[data-cy="reminder-message"]').type("Collect them from the exam section.");
  };

  it("shows the sender, and each reminder's audience, status and counts", () => {
    stub({
      list: [
        reminder({ id: 11, status: "SENDING", sent: 40, failed: 2, departmentCode: "CS",
                   lastError: "Daily email limit (280 per 24h) reached; the rest send on a later run." }),
        reminder({ id: 10, status: "SENT", sent: 142 }),
      ],
    });
    visit();

    cy.get('[data-cy="reminder-sender"]').should("contain", "MSRIT Backlog Portal <portal@gmail.com>");
    cy.get('[data-cy="reminder-mail-off"]').should("not.exist");
    cy.get('[data-cy="reminder-row-11"]').should("contain", "Computer Science & Engineering (CSE)");
    cy.get('[data-cy="reminder-status-11"]').should("have.text", "Sending");
    cy.get('[data-cy="reminder-counts-11"]').should("have.text", "40 sent · 2 failed");
    cy.get('[data-cy="reminder-error-11"]').should("contain", "Daily email limit");
    cy.get('[data-cy="reminder-row-10"]').should("contain", "All departments").and("contain", "IST");
    cy.get('[data-cy="reminder-status-10"]').should("have.text", "Sent");
    // a finished reminder cannot be cancelled
    cy.get('[data-cy="reminder-cancel-10"]').should("not.exist");
  });

  it("warns when mail or the scheduler token is not configured", () => {
    stub({ config: { mailConfigured: false, senderEmail: "", triggerConfigured: false } });
    visit();

    cy.get('[data-cy="reminder-mail-off"]').should("contain", "BREVO_API_KEY");
    cy.get('[data-cy="reminder-trigger-off"]').should("contain", "CRON_TOKEN");
  });

  it("refuses to schedule without a cycle, without sending", () => {
    stub();
    cy.intercept("POST", "/api/admin/reminders", cy.spy().as("createSpy"));
    visit();

    cy.get('[data-cy="reminder-schedule"]').click();
    cy.get('[data-cy="reminder-form-error"]').should("contain", "Pick an exam cycle");
    cy.get("@createSpy").should("not.have.been.called");
  });

  it("previews the live count and rendered email, and an edit drops the stale preview", () => {
    stub();
    cy.intercept("POST", "/api/admin/reminders/preview", {
      statusCode: 200,
      body: {
        recipientCount: 2,
        sampleTo: "1ms24cs001@msrit.edu",
        sampleSubject: "Hall tickets",
        sampleBody: "Dear Asha (1MS24CS001),\n\nCollect them from the exam section.",
      },
    }).as("preview");
    visit();

    fillForm();
    cy.get('[data-cy="reminder-department"]').select("Computer Science & Engineering (CSE)");
    cy.get('[data-cy="reminder-preview"]').click();
    cy.wait("@preview").its("request.body").should("include", { examCycleId: 3, departmentId: 1 });
    cy.get('[data-cy="reminder-preview-count"]').should("contain", "Currently 2 students");
    cy.get('[data-cy="reminder-preview-panel"]').should("contain", "Dear Asha (1MS24CS001)");

    cy.get('[data-cy="reminder-subject"]').type("!");
    cy.get('[data-cy="reminder-preview-panel"]').should("not.exist");
  });

  // A slow reply must not land after the form changed: it would show an email that no longer
  // matches the inputs. The stub's delay is what makes the late reply observable.
  it("an edit during a slow preview drops its reply and frees the button", () => {
    stub();
    cy.intercept("POST", "/api/admin/reminders/preview", {
      statusCode: 200,
      delay: 800,
      body: { recipientCount: 9, sampleTo: "x@msrit.edu", sampleSubject: "Old", sampleBody: "STALE BODY" },
    }).as("preview");
    visit();

    fillForm();
    cy.get('[data-cy="reminder-preview"]').click();
    cy.get('[data-cy="reminder-preview"]').should("be.disabled");
    cy.get('[data-cy="reminder-subject"]').type(" (edited)");
    cy.get('[data-cy="reminder-preview"]').should("not.be.disabled");
    cy.wait(1000);
    cy.get('[data-cy="reminder-preview-panel"]').should("not.exist");
    cy.contains("STALE BODY").should("not.exist");
  });

  it("schedules with the IST wall-clock time and reloads the list", () => {
    stub();
    cy.intercept("POST", "/api/admin/reminders", (req) => {
      server.list = [reminder({ id: 12 })];
      req.reply({ statusCode: 201, body: server.list[0] });
    }).as("create");
    visit();

    fillForm();
    cy.get('[data-cy="reminder-schedule"]').click();
    cy.wait("@create").its("request.body").should("deep.equal", {
      examCycleId: 3,
      departmentId: null,
      sendAt: "2026-10-05T09:00",
      subject: "Hall tickets",
      message: "Collect them from the exam section.",
    });
    cy.get('[data-cy="reminder-notice"]').should("contain", "Reminder scheduled");
    cy.get('[data-cy="reminder-row-12"]').should("exist");
    cy.get('[data-cy="reminder-subject"]').should("have.value", "");
  });

  it("shows the server's refusal when scheduling fails", () => {
    stub();
    cy.intercept("POST", "/api/admin/reminders", {
      statusCode: 400,
      body: { message: "The send time is in the past." },
    }).as("create");
    visit();

    fillForm();
    cy.get('[data-cy="reminder-schedule"]').click();
    cy.wait("@create");
    cy.get('[data-cy="reminder-form-error"]').should("contain", "The send time is in the past.");
  });

  it("cancels in two steps, and a refused cancel stays armed with the reason", () => {
    stub({ list: [reminder({ id: 10 }), reminder({ id: 11, status: "SENDING" })] });
    let calls = 0;
    cy.intercept("POST", "/api/admin/reminders/*/cancel", (req) => {
      calls += 1;
      if (req.url.includes("/11/")) {
        req.reply({ statusCode: 409, body: { message: "This reminder is already sent." } });
        return;
      }
      server.list = server.list.map((r) => (r.id === 10 ? { ...r, status: "CANCELLED" } : r));
      req.reply({ statusCode: 200, body: server.list[0] });
    }).as("cancel");
    visit();

    cy.get('[data-cy="reminder-cancel-10"]').click();
    cy.wrap(null).then(() => expect(calls, "arming sends nothing").to.equal(0));
    cy.get('[data-cy="reminder-cancel-confirm-10"]').click();
    cy.wait("@cancel");
    cy.get('[data-cy="reminder-status-10"]').should("have.text", "Cancelled");

    cy.get('[data-cy="reminder-cancel-11"]').click();
    cy.get('[data-cy="reminder-cancel-confirm-11"]').click();
    cy.wait("@cancel");
    cy.get('[data-cy="reminder-row-11"]').should("contain", "This reminder is already sent.");
    cy.get('[data-cy="reminder-cancel-confirm-11"]').should("exist");
  });

  it("lists the failed students on demand", () => {
    stub({ list: [reminder({ id: 10, status: "SENT", sent: 5, failed: 1 })] });
    cy.intercept("GET", "/api/admin/reminders/10/failures", {
      statusCode: 200,
      body: [{ rollNo: "1MS24CS004", email: "bad@example", error: "Brevo 400: email is not valid" }],
    }).as("failures");
    visit();

    cy.get('[data-cy="reminder-failures-10"]').click();
    cy.wait("@failures");
    cy.get('[data-cy="reminder-failure-list-10"]').should("contain", "1MS24CS004").and("contain", "email is not valid");
    cy.get('[data-cy="reminder-failures-10"]').click();
    cy.get('[data-cy="reminder-failure-list-10"]').should("not.exist");
  });

  it("sends a test only to a valid address, and reports the outcome", () => {
    stub();
    cy.intercept("POST", "/api/admin/reminders/test", (req) => {
      if (req.body.to === "down@example.com") {
        req.reply({ statusCode: 502, body: { message: "The email service refused it: Brevo 401" } });
        return;
      }
      req.reply({ statusCode: 200, body: { status: "sent" } });
    }).as("testSend");
    visit();

    cy.get('[data-cy="reminder-subject"]').type("Hall tickets");
    cy.get('[data-cy="reminder-message"]').type("Collect them.");
    cy.get('[data-cy="reminder-test-to"]').type("not-an-email");
    cy.get('[data-cy="reminder-test-send"]').click();
    cy.get('[data-cy="reminder-test-result"]').should("contain", "valid email");

    cy.get('[data-cy="reminder-test-to"]').clear().type("me@gmail.com");
    cy.get('[data-cy="reminder-test-send"]').click();
    cy.wait("@testSend").its("request.body").should("deep.equal", {
      to: "me@gmail.com",
      subject: "Hall tickets",
      message: "Collect them.",
    });
    cy.get('[data-cy="reminder-test-result"]').should("contain", "Test sent to me@gmail.com");

    cy.get('[data-cy="reminder-test-to"]').clear().type("down@example.com");
    cy.get('[data-cy="reminder-test-send"]').click();
    cy.wait("@testSend");
    cy.get('[data-cy="reminder-test-result"]').should("contain", "Brevo 401");
  });
});
