package com.college.backlog.controller;

import com.college.backlog.controller.dto.BatchResult;
import com.college.backlog.controller.dto.ProctorAssignRequest;
import com.college.backlog.controller.dto.ProgressionRowResult;
import com.college.backlog.controller.dto.StudentImportRequest;
import com.college.backlog.controller.dto.StudentImportRow;
import com.college.backlog.model.Department;
import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.service.CallerScope;
import com.college.backlog.service.StudentManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * A {@link NumberFormatException} raised inside a batch row loop is a SERVER bug, and must never be
 * reported to the admin as if their row were malformed.
 *
 * <p>NFE extends {@link IllegalArgumentException}, and both loops here surface {@code getMessage()}
 * for IAE because their own validation throws it with curated sentences. Without a dedicated NFE
 * clause above that one, a stray {@code For input string: "null"} lands in the results table as a
 * row-data problem, is counted as an ordinary bad row, and is never logged — so the real defect is
 * invisible. The two subject-side loops already had this clause; these two did not.
 *
 * <p>Plain Mockito, no Spring context and no database: the loops are ordinary Java, and the
 * controllers use field injection so {@code @InjectMocks} reaches them. Stubbing is kept to the
 * minimum each path actually calls — Mockito's STRICT_STUBS fails on an unused stub, and an
 * unstubbed call inside the try would be swallowed by the very catch-all under test.
 */
@ExtendWith(MockitoExtension.class)
class BatchRowFailureReportingTest {

    private static final String ROLL = "1MS24CS001";

    // ---- student import ----

    @Mock private CallerScope callerScope;
    @Mock private StudentManagementService studentService;
    @Mock private StudentRepository studentRepository;
    @Mock private com.college.backlog.service.ProctorScopeService proctorScope;
    @Mock private com.college.backlog.repository.DepartmentRepository departmentRepository;
    @InjectMocks private StudentManagementController studentController;

    private static User user(UserRole role, String username, String deptCode) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", 1L);
        u.setUsername(username);
        u.setRole(role);
        if (deptCode != null) {
            Department d = new Department();
            d.setId(1L);
            d.setCode(deptCode);
            u.setDepartment(d);
        }
        return u;
    }

    @Test
    void aServerSideNumberFormatExceptionIsNotReportedAsABadStudentRow() {
        // ADMIN so callerDeptCode() short-circuits to null and no department stubbing is needed
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.ADMIN, "admin", null));
        when(studentService.normalizeUsn(anyString())).thenReturn(ROLL);
        doThrow(new NumberFormatException("For input string: \"null\""))
            .when(studentService).createStudent(any(), anyString());

        StudentImportRow row = new StudentImportRow();
        row.setRollNo(ROLL);
        row.setName("Asha Rao");
        row.setDateOfBirth(LocalDate.of(2006, 4, 12));
        row.setCurrentSemester(2);
        row.setEntrySemester(1);
        StudentImportRequest req = new StudentImportRequest();
        req.setRows(List.of(row));

        BatchResult<ProgressionRowResult> res = studentController.importRows(req, null);

        assertThat(res.getErrors()).isEqualTo(1);
        ProgressionRowResult reported = res.getResults().get(0);
        assertThat(reported.getStatus()).isEqualTo("ERROR");
        assertThat(reported.getMessage()).isEqualTo("Could not import this row.");
        // the raw JDK wording is what leaks when the NFE clause is missing
        assertThat(reported.getMessage()).doesNotContain("For input string");
    }

    // ---- proctor claim ----

    @Mock private ProctorAssignmentRepository assignmentRepository;
    @Mock private com.college.backlog.repository.UserRepository userRepository;
    @Mock private com.college.backlog.service.AdminAuditService auditService;
    @InjectMocks private ProctorAssignmentController proctorController;

    @Test
    void aServerSideNumberFormatExceptionIsNotReportedAsABadProctorClaim() {
        // a PROCTOR actor resolves to itself, so resolveTargetProctor touches no repository
        User proctor = user(UserRole.PROCTOR, "proctor-user", "CS");
        when(callerScope.requireActor(any())).thenReturn(proctor);
        when(studentService.normalizeUsn(anyString())).thenReturn(ROLL);
        when(studentRepository.existsById(ROLL)).thenReturn(true);
        when(assignmentRepository.findById(ROLL)).thenReturn(Optional.empty());
        doThrow(new NumberFormatException("For input string: \"null\""))
            .when(assignmentRepository).save(any(ProctorAssignment.class));

        ProctorAssignRequest req = new ProctorAssignRequest();
        req.setRollNos(List.of(ROLL));

        BatchResult<ProgressionRowResult> res = proctorController.assign(req, null);

        assertThat(res.getErrors()).isEqualTo(1);
        ProgressionRowResult reported = res.getResults().get(0);
        assertThat(reported.getStatus()).isEqualTo("ERROR");
        // NOT the catch-all's "it may have just been claimed": naming a race as the cause of a
        // server bug is a confident false explanation
        assertThat(reported.getMessage()).isEqualTo("Could not assign this student.");
        assertThat(reported.getMessage()).doesNotContain("For input string");
    }
}
