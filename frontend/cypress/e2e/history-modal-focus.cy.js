// The registration-history dialog's keyboard contract: Escape closes and returns focus to the
// trigger, focus lands inside on open, and Tab is TRAPPED.
//
// The trap is the load-bearing part. aria-modal="true" tells assistive tech the rest of the page is
// inert, so Tab reaching the page behind makes the markup a lie — a keyboard or screen-reader user
// ends up on content they've been told isn't there.
//
// Cypress has no native Tab press and no tab plugin is installed (deliberately — not worth a
// dependency for this), so these dispatch a synthetic Tab keydown.
//
// That makes "focus is still inside the dialog" a VACUOUS assertion, and mutation testing caught it
// on 2026-08-17: a synthetic Tab moves nothing by itself, so with the trap disabled focus simply
// stayed on the close button and the containment check passed anyway. What proves interception is
// `defaultPrevented` — the handler must cancel the browser's default Tab. The event therefore MUST
// be constructed `cancelable: true`, or preventDefault() is a silent no-op and the assertion can
// never hold.
//
// A wrap-DESTINATION assertion (last -> first) would be stronger still, but the dialog currently has
// exactly one focusable descendant, so Tab wraps to itself and the destination is trivially
// unchanged. Add that assertion if the dialog ever gains a second control.
describe("History modal focus handling", () => {
  const row = {
    regId: "REG-2026-1001",
    rollNo: "1MS22CS001",
    studentName: "Student One",
    semester: 4,
    yearOfJoining: 2022,
    subjects: ["Data Structures"],
    status: "SUBMITTED",
    verifiedBy: null,
    registeredAt: "2026-04-20T10:20:00",
  };

  // AdminPage fires all of these on mount; an unstubbed /api/admin call 401s and signs the session
  // out mid-test, which then fails on an unrelated assertion
  const stubAdminPage = () => {
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [row], totalElements: 1, totalPages: 1, number: 0 },
    });
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 1, submitted: 1, verified: 0, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
    cy.intercept("GET", `/api/admin/registrations/${row.regId}/events`, {
      statusCode: 200,
      body: [
        { action: "SUBMITTED", actor: "1MS22CS001", actorRole: "STUDENT", timestamp: "2026-04-20T10:20:00", note: null },
      ],
    }).as("getEvents");
  };

  const openHistory = () => {
    stubAdminPage();
    cy.visitAsAdmin("/admin");
    cy.contains("1MS22CS001").should("be.visible");
    cy.get(`[data-cy="history-${row.regId}"]`).click();
    cy.get('[data-cy="history-dialog"]').should("be.visible");
  };

  // cancelable: true is load-bearing — see the header note
  const pressTab = (shiftKey = false) =>
    cy.document().then((doc) => {
      const ev = new KeyboardEvent("keydown", {
        key: "Tab",
        shiftKey,
        bubbles: true,
        cancelable: true,
      });
      doc.activeElement.dispatchEvent(ev);
      const dialog = doc.querySelector('[data-cy="history-dialog"]');
      return { defaultPrevented: ev.defaultPrevented, inside: dialog.contains(doc.activeElement) };
    });

  it("moves focus into the dialog on open", () => {
    openHistory();
    cy.focused().should("have.attr", "data-cy", "history-close");
  });

  it("intercepts Tab rather than letting it walk to the page behind", () => {
    openHistory();
    pressTab().then(({ defaultPrevented, inside }) => {
      // defaultPrevented is the real assertion: it is false the moment the trap is removed
      expect(defaultPrevented, "Tab was cancelled by the trap").to.be.true;
      expect(inside, "focus stayed in the dialog").to.be.true;
    });
  });

  it("intercepts Shift+Tab too", () => {
    openHistory();
    pressTab(true).then(({ defaultPrevented, inside }) => {
      expect(defaultPrevented, "Shift+Tab was cancelled by the trap").to.be.true;
      expect(inside, "focus stayed in the dialog").to.be.true;
    });
  });

  it("pulls focus back if it has already escaped the dialog", () => {
    openHistory();
    // simulate focus sitting outside (a backdrop click, or a browser that moved it)
    cy.get('[data-cy="admin-search"]').then(($el) => $el[0].focus());
    pressTab();
    cy.document().then((doc) => {
      const dialog = doc.querySelector('[data-cy="history-dialog"]');
      expect(dialog.contains(doc.activeElement), "focus was pulled back in").to.be.true;
    });
  });

  it("Escape closes the dialog and returns focus to the trigger", () => {
    openHistory();
    cy.get("body").type("{esc}");
    cy.get('[data-cy="history-dialog"]').should("not.exist");
    cy.focused().should("have.attr", "data-cy", `history-${row.regId}`);
  });
});
