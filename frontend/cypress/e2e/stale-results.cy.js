// A result card describes ONE run. When the next run failed — or never left the browser because
// validation rejected it — the previous card stayed up, so "Preview — 2 created" described a run
// that never happened, sitting beside the error explaining that it didn't. Same shape for a
// filtered list: a failed re-fetch left the previous department's subjects on screen as the new
// department's.
describe("A failed run never leaves the previous result standing", () => {
  const visitImport = () => {
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] }).as("getDepartments");
    cy.visitAsAdmin("/admin/students?tab=import");
    cy.wait("@getDepartments");
  };

  const CSV = "1MS24CS001,Asha Rao,2006-04-12,9999999999,2,1\n1MS24CS002,Bhavya S,2006-05-02,,2,1";

  const PREVIEW_OK = {
    statusCode: 200,
    body: {
      dryRun: true,
      created: 2,
      skipped: 0,
      errors: 0,
      results: [
        { rollNo: "1MS24CS001", semester: 1, status: "WOULD_CREATE", message: "" },
        { rollNo: "1MS24CS002", semester: 1, status: "WOULD_CREATE", message: "" },
      ],
    },
  };

  it("student import: a failed second run clears the first run's card", () => {
    cy.intercept("POST", "/api/admin/students/import", PREVIEW_OK).as("ok");
    visitImport();

    cy.get('[data-cy="students-import-csv"]').type(CSV);
    cy.get('[data-cy="students-import-preview"]').click();
    cy.wait("@ok");
    cy.contains("Preview — 2 created").should("be.visible");

    // second run fails server-side; the first run's card must not survive it
    cy.intercept("POST", "/api/admin/students/import", {
      statusCode: 500,
      body: { message: "Import failed." },
    }).as("boom");
    cy.get('[data-cy="students-import-preview"]').click();
    cy.wait("@boom");

    cy.contains("Import failed.").should("be.visible");
    cy.contains("Preview — 2 created").should("not.exist");
  });

  it("student import: a client-side rejection also clears it (no request is even sent)", () => {
    cy.intercept("POST", "/api/admin/students/import", PREVIEW_OK).as("ok");
    visitImport();

    cy.get('[data-cy="students-import-csv"]').type(CSV);
    cy.get('[data-cy="students-import-preview"]').click();
    cy.wait("@ok");
    cy.contains("Preview — 2 created").should("be.visible");

    // empty the box: the run is rejected before any request, which used to leave the card up
    cy.get('[data-cy="students-import-csv"]').clear();
    cy.get('[data-cy="students-import-preview"]').click();

    cy.contains("Paste at least one row").should("be.visible");
    cy.contains("Preview — 2 created").should("not.exist");
  });

  it("subject list: a failed re-fetch clears the rows instead of relabelling them", () => {
    cy.intercept("GET", "/api/departments", { statusCode: 200, body: [] }).as("getDepartments");
    cy.intercept("GET", "/api/admin/subjects*", {
      statusCode: 200,
      body: {
        content: [
          {
            id: 1,
            courseCode: "22CSL44",
            subjectName: "Data Structures Lab",
            semester: 4,
            academicYearOffered: 2022,
            credits: 2,
            subjectType: "REGULAR",
          },
        ],
        number: 0,
        totalPages: 1,
        totalElements: 1,
      },
    }).as("subjectsOk");
    cy.visitAsAdmin("/admin/manage-subjects");
    cy.wait("@getDepartments");
    // the list is not fetched on mount — `subjects` starts null ("not loaded yet")
    cy.get('[data-cy="subjects-load"]').click();
    cy.wait("@subjectsOk");
    cy.contains("Data Structures Lab").should("be.visible");

    cy.intercept("GET", "/api/admin/subjects*", { statusCode: 500, body: {} }).as("subjectsBoom");
    cy.get('[data-cy="subjects-load"]').click();
    cy.wait("@subjectsBoom");

    cy.contains("Could not load subjects").should("be.visible");
    cy.contains("Data Structures Lab").should("not.exist");
    // and not the other lie either — [] would claim the filters matched nothing
    cy.get('[data-cy="subjects-empty"]').should("not.exist");
  });

  // AdminPage keeps `registrations` in a plain array with no "not loaded" state, so a failed
  // re-fetch used to leave the previous filter's rows up — Verify, Reject and the export
  // checkbox all still live on them — under a banner saying the load failed.
  it("admin dashboard: a failed re-fetch clears the rows and does not claim the filter matched nothing", () => {
    const row = {
      regId: "REG-2026-1001",
      rollNo: "1MS22CS001",
      studentName: "Student One",
      semester: 4,
      yearOfJoining: 2022,
      subjects: ["Data Structures"],
      status: "SUBMITTED",
      verifiedBy: null,
      registeredAt: "2026-04-20T10:20:00Z",
      canVerify: true,
    };
    cy.intercept("GET", "/api/admin/registrations/summary-counts*", {
      statusCode: 200,
      body: { total: 1, submitted: 1, verified: 0, rejected: 0 },
    });
    cy.intercept("GET", "/api/admin/exam-cycles*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/subjects-for-filter*", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/departments", { statusCode: 200, body: [] });
    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 200,
      body: { content: [row], totalElements: 1, totalPages: 1, number: 0 },
    }).as("regsOk");

    cy.visitAsAdmin("/admin");
    cy.wait("@regsOk");
    cy.contains("Student One").should("be.visible");

    cy.intercept("GET", "/api/admin/registrations*", {
      statusCode: 500,
      body: { message: "Registrations backend is down." },
    }).as("regsBoom");
    cy.get('[data-cy="admin-search"]').type("zzz");
    cy.get('[data-cy="admin-filters-apply"]').click();
    cy.wait("@regsBoom");

    cy.get('[data-cy="admin-load-error"]').should("contain", "Registrations backend is down.");
    cy.contains("Student One").should("not.exist");
    // and not the other lie — [] alone would render "No registrations match the current filters"
    cy.get('[data-cy="admin-empty"]').should("not.exist");
  });

  it("student list: a failed re-fetch clears the rows instead of relabelling them", () => {
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [{ id: 1, deptName: "Computer Science", code: "CS" }],
    }).as("getDepartments");
    cy.intercept("GET", "/api/admin/students*", {
      statusCode: 200,
      body: {
        content: [{
          rollNo: "1MS24CS001", name: "Asha Rao", branch: "CS",
          currentSemester: 2, entrySemester: 1, yearOfJoining: 2024,
          email: "1ms24cs001@msrit.edu", phone: null,
        }],
        number: 0, totalPages: 1, totalElements: 1,
      },
    }).as("studentsOk");

    cy.visitAsAdmin("/admin/students");
    cy.wait("@getDepartments");
    cy.get('[data-cy="students-load"]').click();
    cy.wait("@studentsOk");
    cy.contains("Asha Rao").should("be.visible");

    cy.intercept("GET", "/api/admin/students*", { statusCode: 500, body: {} }).as("studentsBoom");
    cy.get('[data-cy="students-load"]').click();
    cy.wait("@studentsBoom");

    cy.contains("Could not load students").should("be.visible");
    cy.contains("Asha Rao").should("not.exist");
    cy.get('[data-cy="students-empty"]').should("not.exist");
  });
});

// The other half of the same problem. Here nothing FAILS — a reply simply arrives after the
// inputs it was fetched for are gone, and writes itself over the screen anyway. Each of these
// asserts the late reply never lands, which is only observable because the stub is slow.
describe("A superseded reply never lands on screen", () => {
  const stubBulkDepartments = () =>
    cy.intercept("GET", "/api/departments", {
      statusCode: 200,
      body: [
        { id: 1, deptName: "Computer Science", code: "CS" },
        { id: 2, deptName: "Civil", code: "CV" },
      ],
    }).as("getDepartments");

  const subjectPage = (name) => ({
    content: [{
      id: name.length, courseCode: "22XX11", subjectName: name, semester: 4,
      academicYearOffered: 2022, credits: 3, subjectType: "REGULAR",
    }],
    number: 0, totalPages: 1, totalElements: 1,
  });

  // The Load button is deliberately NOT disabled while a load runs — a disabled trigger means
  // nothing can supersede, and the filters stay editable regardless. This table has no
  // Department column, so the old department's rows under the new selection are undetectable.
  it("subject list: the slow reply for the old department never replaces the new one's rows", () => {
    stubBulkDepartments();
    cy.intercept("GET", "/api/admin/subjects*", (req) => {
      if (req.query.deptId === "1") {
        req.reply({ delay: 1200, statusCode: 200, body: subjectPage("Old Department Subject") });
      } else {
        req.reply({ statusCode: 200, body: subjectPage("New Department Subject") });
      }
    }).as("subjects");

    cy.visitAsAdmin("/admin/manage-subjects");
    cy.wait("@getDepartments");

    cy.get('[data-cy="subjects-dept"]').select("Computer Science");
    cy.get('[data-cy="subjects-load"]').click();
    // supersede it before the slow reply can arrive
    cy.get('[data-cy="subjects-dept"]').select("Civil");
    cy.get('[data-cy="subjects-load"]').click();

    cy.contains("New Department Subject").should("be.visible");
    // outlast the slow stub: without the abort its reply lands here and overwrites the rows
    cy.wait(1500);
    cy.contains("Old Department Subject").should("not.exist");
    cy.contains("New Department Subject").should("be.visible");
  });

  const stubHistory = (content) =>
    cy.intercept("GET", "/api/admin/progression/bulk?*", {
      statusCode: 200,
      body: { content, number: 0, totalPages: 1, totalElements: content.length },
    }).as("getHistory");

  const historyRow = (batchId, promotedCount) => ({
    batchId, actor: "admin", runAt: "2026-08-17T10:00:00Z",
    filterSemester: null, filterDeptCode: null,
    promotedCount, excludedCount: 1, skippedCount: 0,
  });

  // Editing a filter calls resetPreview(), which clears the card — but the request was still in
  // flight, so its reply put the old cohort's count straight back up, now under filters it was
  // never counted for. Commit then sends the new filters with that old expectedCount.
  it("bulk progression: an edit mid-preview cancels it, so the old count cannot come back", () => {
    stubBulkDepartments();
    stubHistory([]);
    cy.intercept("POST", "/api/admin/progression/bulk/preview", {
      delay: 1200,
      statusCode: 200,
      body: { promoteCount: 42, notPromoted: [], atMaxCount: 0 },
    }).as("preview");

    cy.visitAsAdmin("/admin/students?tab=bulk", { role: "ADMIN" });
    cy.wait("@getDepartments");
    cy.wait("@getHistory");

    cy.get('[data-cy="bulk-preview"]').click();
    // change the cohort while the preview is still running
    cy.get('[data-cy="bulk-semester"]').select("Semester 4");

    cy.wait(1500);
    cy.get('[data-cy="bulk-preview-result"]').should("not.exist");
    // and the form is usable again — the cancelled attempt must not strand the busy flag
    cy.get('[data-cy="bulk-preview"]').should("not.be.disabled");
  });

  // The run rows are not disabled while a detail loads, so run #1's reply used to paint its
  // held-back rows under run #2's heading and clear the spinner a reply early.
  it("bulk progression: opening a second run while the first loads shows only the second", () => {
    stubBulkDepartments();
    stubHistory([historyRow(1, 111), historyRow(2, 222)]);
    cy.intercept("GET", "/api/admin/progression/bulk/1", {
      delay: 1200,
      statusCode: 200,
      body: {
        batch: { batchId: 1, promotedCount: 111, excludedCount: 1, skippedCount: 0 },
        notPromoted: [{ rollNo: "1MS24CS111", semesterFrom: 4, outcome: "EXCLUDED_BY_ADMIN" }],
      },
    }).as("batchSlow");
    cy.intercept("GET", "/api/admin/progression/bulk/2", {
      statusCode: 200,
      body: {
        batch: { batchId: 2, promotedCount: 222, excludedCount: 1, skippedCount: 0 },
        notPromoted: [{ rollNo: "1MS24CS222", semesterFrom: 6, outcome: "EXCLUDED_BY_ADMIN" }],
      },
    }).as("batchFast");

    cy.visitAsAdmin("/admin/students?tab=bulk", { role: "ADMIN" });
    cy.wait("@getDepartments");
    cy.wait("@getHistory");

    cy.get('[data-cy="bulk-history-row-1"]').click();
    cy.get('[data-cy="bulk-history-row-2"]').click();

    cy.get('[data-cy="bulk-history-detail"]').should("contain", "1MS24CS222");
    cy.wait(1500);
    cy.get('[data-cy="bulk-history-detail"]').should("contain", "1MS24CS222");
    cy.get('[data-cy="bulk-history-detail"]').should("not.contain", "1MS24CS111");
  });
});
