package com.college.backlog.controller;

import com.college.backlog.controller.dto.BatchResult;
import com.college.backlog.controller.dto.ProctorAssignRequest;
import com.college.backlog.controller.dto.ProgressionRowResult;
import com.college.backlog.controller.dto.StudentCreateRequest;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * A create that loses a race — the row appeared between the existence check and the INSERT — must
 * report the same outcome the check would have, and any OTHER constraint must still read as a
 * failure. {@code AssignedIdInsertTest} proves the INSERT really fails on the key; this pins what
 * each caller makes of that failure.
 *
 * <p>Plain Mockito, like {@link BatchRowFailureReportingTest}: the loops are ordinary Java.
 */
@ExtendWith(MockitoExtension.class)
class ConcurrentCreateReportingTest {

    private static final String ROLL = "1MS24CS001";

    @Mock private CallerScope callerScope;
    @Mock private StudentManagementService studentService;
    @Mock private StudentRepository studentRepository;
    @Mock private com.college.backlog.service.ProctorScopeService proctorScope;
    @Mock private com.college.backlog.repository.DepartmentRepository departmentRepository;
    @InjectMocks private StudentManagementController studentController;

    @Mock private ProctorAssignmentRepository assignmentRepository;
    @Mock private com.college.backlog.repository.UserRepository userRepository;
    @Mock private com.college.backlog.service.AdminAuditService auditService;
    @InjectMocks private ProctorAssignmentController proctorController;

    /** The shape Postgres produces, nested the way Spring's translation wraps it. */
    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
            new RuntimeException("ERROR: duplicate key value violates unique constraint \""
                + constraint + "\""));
    }

    private static User user(UserRole role, long id, String username, String deptCode) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
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

    // ---- student import ----

    private BatchResult<ProgressionRowResult> importOneRowFailingWith(DataIntegrityViolationException e) {
        // ADMIN so callerDeptCode() short-circuits to null and no department stubbing is needed
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.ADMIN, 1L, "admin", null));
        when(studentService.normalizeUsn(anyString())).thenReturn(ROLL);
        doThrow(e).when(studentService).createStudent(any(), anyString());

        StudentImportRow row = new StudentImportRow();
        row.setRollNo(ROLL);
        row.setName("Asha Rao");
        row.setDateOfBirth(LocalDate.of(2006, 4, 12));
        row.setCurrentSemester(2);
        row.setEntrySemester(1);
        StudentImportRequest req = new StudentImportRequest();
        req.setRows(List.of(row));
        return studentController.importRows(req, null);
    }

    @Test
    void anImportRowRacedByAConcurrentCreateIsSkippedLikeTheExistenceCheck() {
        BatchResult<ProgressionRowResult> res = importOneRowFailingWith(violationOf("students_pkey"));

        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getErrors()).isZero();
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("SKIPPED_EXISTS");
    }

    @Test
    void anImportRowFailingAnyOtherConstraintIsStillAnError() {
        BatchResult<ProgressionRowResult> res = importOneRowFailingWith(violationOf("chk_students_semester"));

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("ERROR");
        assertThat(res.getResults().get(0).getMessage()).isEqualTo("Could not import this row.");
    }

    // ---- single create ----

    @Test
    void aSingleCreateRacedByAConcurrentCreateIsTheSame409AsTheExistenceCheck() {
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.ADMIN, 1L, "admin", null));
        when(studentService.normalizeUsn(anyString())).thenReturn(ROLL);
        doThrow(violationOf("students_pkey")).when(studentService).createStudent(any(), anyString());

        StudentCreateRequest req = new StudentCreateRequest();
        req.setRollNo(ROLL);

        assertThatThrownBy(() -> studentController.create(req, null))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).isEqualTo("A student with USN " + ROLL + " already exists.");
            });
    }

    // ---- proctor claim ----

    private BatchResult<ProgressionRowResult> claimLosingTheRaceTo(long holderId) {
        // a PROCTOR actor resolves to itself, so resolveTargetProctor touches no repository
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.PROCTOR, 1L, "proctor-user", "CS"));
        when(studentService.normalizeUsn(anyString())).thenReturn(ROLL);
        when(studentRepository.existsById(ROLL)).thenReturn(true);
        // empty at the pre-check, present once the competing claim has committed
        when(assignmentRepository.findById(ROLL))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(new ProctorAssignment(ROLL, holderId, "hod")));
        doThrow(violationOf("proctor_students_pkey"))
            .when(assignmentRepository).saveAndFlush(any(ProctorAssignment.class));

        ProctorAssignRequest req = new ProctorAssignRequest();
        req.setRollNos(List.of(ROLL));
        return proctorController.assign(req, null);
    }

    @Test
    void aClaimRacedByAnotherProctorNamesTheHolderLikeThePreCheck() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(UserRole.PROCTOR, 2L, "other-proctor", "CS")));

        BatchResult<ProgressionRowResult> res = claimLosingTheRaceTo(2L);

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("ERROR");
        assertThat(res.getResults().get(0).getMessage()).isEqualTo("Already assigned to other-proctor.");
    }

    /** The holder re-read runs inside the catch, outside its sibling clauses: a failure there must
     *  become this row's ERROR, not escape and abort the rows after it. */
    @Test
    void aFailedHolderReadAfterALostRaceIsOneErrorRowAndTheBatchContinues() {
        String lost = "1MS24CS001";
        String next = "1MS24CS002";
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.PROCTOR, 1L, "proctor-user", "CS"));
        when(studentService.normalizeUsn(anyString())).thenAnswer(i -> i.getArgument(0));
        when(studentRepository.existsById(anyString())).thenReturn(true);
        // lost row: empty at the pre-check, then Neon drops during the re-read
        when(assignmentRepository.findById(lost))
            .thenReturn(Optional.empty())
            .thenThrow(new DataAccessResourceFailureException("connection reset"));
        when(assignmentRepository.findById(next)).thenReturn(Optional.empty());
        // ONE any() stub branching inside: an unmatched call under STRICT_STUBS throws, and the
        // catch-all would swallow that into a spurious ERROR row
        doAnswer(inv -> {
            ProctorAssignment a = inv.getArgument(0);
            if (lost.equals(a.getRollNo())) {
                throw violationOf("proctor_students_pkey");
            }
            return a;
        }).when(assignmentRepository).saveAndFlush(any(ProctorAssignment.class));

        ProctorAssignRequest req = new ProctorAssignRequest();
        req.setRollNos(List.of(lost, next));
        BatchResult<ProgressionRowResult> res = proctorController.assign(req, null);

        assertThat(res.getResults()).hasSize(2);
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("ERROR");
        assertThat(res.getResults().get(0).getMessage()).isEqualTo("Could not assign this student.");
        assertThat(res.getResults().get(1).getStatus()).isEqualTo("CREATED");
    }

    @Test
    void aClaimRacedByTheSameProctorIsSkipped() {
        BatchResult<ProgressionRowResult> res = claimLosingTheRaceTo(1L);

        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getErrors()).isZero();
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("SKIPPED_EXISTS");
    }
}
