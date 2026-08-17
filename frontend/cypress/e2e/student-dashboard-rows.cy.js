// The dashboard's registration ROWS. Every other student spec stubs /api/student/registrations
// with `[]`, so the row body — status pill, subject line, timestamp — had no coverage at all.
// That gap is why this spec exists: `reg.subjects` is rendered BARE (no `|| []`), on the contract
// that StudentController.java:161 always builds the list from a stream collect. A bare read is the
// right call there — a guard would turn a contract break into a silent "No subjects" on a form
// that has some — but it is only safe if something actually renders a row.
describe("Student dashboard — registration rows", () => {
  const profile = {
    rollNo: "1MS22CS001",
    name: "Student One",
    email: "student1@msrit.edu",
    branch: "Computer Science",
    phone: "9876543210",
    currentSemester: 4,
    eligibleSemesters: [1, 2, 3, 4],
  };

  const registration = {
    regId: "REG-1",
    status: "SUBMITTED",
    examCycle: "Dec 2025",
    subjects: ["Data Structures (22CSL44)", "Discrete Maths (22MAT41)"],
    registeredAt: "2025-12-01T10:00:00Z",
  };

  const visitDashboard = (registrations) => {
    cy.intercept("GET", "/api/student/me", { statusCode: 200, body: profile });
    cy.intercept("GET", "/api/student/registrations", {
      statusCode: 200,
      body: registrations,
    }).as("getMyRegistrations");
    cy.visitAsStudent("/student", { rollNo: profile.rollNo, name: profile.name });
    cy.wait("@getMyRegistrations");
  };

  it("lists a registration's subjects, status and cycle", () => {
    visitDashboard([registration]);

    cy.contains("Data Structures (22CSL44), Discrete Maths (22MAT41)").should("be.visible");
    cy.contains("SUBMITTED").should("be.visible");
    cy.contains("Dec 2025").should("be.visible");
  });

  it("says 'No subjects' for a registration with an empty subject list", () => {
    // The empty list is a real server state (`[]` from the stream collect), unlike a MISSING
    // field — so it must read as "No subjects", not as a crash and not as a blank line.
    visitDashboard([{ ...registration, subjects: [] }]);

    cy.contains("No subjects").should("be.visible");
    cy.contains("SUBMITTED").should("be.visible");
  });
});
