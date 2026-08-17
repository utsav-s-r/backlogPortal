// A dept-scoped user's department pin resolves by ID, not by name — `findOwnDepartment`
// (lib/session.js). The name is display-only and editable, so pinning on it silently un-pinned
// every signed-in dept user the moment a department was renamed; `adminDepartmentId` is the fix.
//
// That branch had NO coverage before this spec: no spec seeded `adminDepartmentId` at all, so
// every dept-scoped test exercised only the name fallback. The gap was invisible while the
// session seed was copy-pasted 15 times — it became obvious once `cy.visitAsAdmin` made the
// session one object you could read.
//
// Observable under test: AddStudentTab's USN hint, which reads the resolved department's `code`
// (AddStudentTab.jsx:41,108). Resolved -> the hint names the branch code; unresolved -> no hint.
describe("Department pin resolves by id, not by the editable name", () => {
  const RENAMED = [{ id: 1, deptName: "Computer Science & Engineering", code: "CS" }];
  const CURRENT = [{ id: 1, deptName: "Computer Science", code: "CS" }];

  const stubDepartments = (body) =>
    cy.intercept("GET", "/api/departments", { statusCode: 200, body }).as("getDepartments");

  const HINT = "Your USNs must use the CS branch code.";

  it("stays pinned after the department is renamed, when the session carries the id", () => {
    stubDepartments(RENAMED);
    // Session was issued BEFORE the rename, so it holds the old name — plus the stable id.
    cy.visitAsAdmin("/admin/students?tab=add", {
      role: "HOD",
      username: "hodcse",
      department: "Computer Science",
      departmentId: 1,
    });
    cy.wait("@getDepartments");

    cy.contains(HINT).should("be.visible");
  });

  it("falls back to the name for a pre-fix session that has no id", () => {
    stubDepartments(CURRENT);
    // `adminDepartmentId` only exists for sessions created after the fix; the rest must keep
    // working by name for the remainder of their (max 1h) window rather than un-pinning mid-task.
    cy.visitAsAdmin("/admin/students?tab=add", {
      role: "HOD",
      username: "hodcse",
      department: "Computer Science",
    });
    cy.wait("@getDepartments");

    cy.contains(HINT).should("be.visible");
  });

  it("cannot resolve a renamed department without the id — the case the id fix exists for", () => {
    stubDepartments(RENAMED);
    cy.visitAsAdmin("/admin/students?tab=add", {
      role: "HOD",
      username: "hodcse",
      department: "Computer Science",
    });
    cy.wait("@getDepartments");

    // Negative control. Without it, test 1 would still pass if the id branch were deleted and the
    // name happened to match — it is what makes test 1 evidence for the id path specifically.
    cy.contains(HINT).should("not.exist");
  });
});
