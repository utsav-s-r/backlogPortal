package com.college.backlog.controller;

import com.college.backlog.controller.dto.ProctorAssignRequest;
import com.college.backlog.controller.dto.StudentImportRequest;
import com.college.backlog.controller.dto.StudentImportRow;
import com.college.backlog.controller.dto.SubjectCloneApplyRequest;
import com.college.backlog.controller.dto.SubjectImportRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.service.AdminAuditService;
import com.college.backlog.service.Batches;
import com.college.backlog.service.CallerScope;
import com.college.backlog.service.ProctorScopeService;
import com.college.backlog.service.StudentManagementService;
import com.college.backlog.service.SubjectCloneService;
import com.college.backlog.service.SubjectImportService;
import com.college.backlog.service.SubjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * All four bulk endpoints refuse an oversized batch with a 400 and write NOTHING.
 *
 * <p>"Writes nothing" is the half worth asserting: a cap inside the row loop, or one that truncated,
 * would still answer 200 and look like success. Each case verifies the collaborator that performs
 * the write was never reached, not just the status.
 *
 * <p>The cap sits AFTER scope resolution so an out-of-scope caller gets 403 regardless of batch
 * size — hence each case stubs a caller who clears the scope check, then oversizes the batch.
 *
 * <p>Plain Mockito: the handlers are ordinary Java and the controllers use field injection.
 */
@ExtendWith(MockitoExtension.class)
class BatchSizeLimitTest {

    private static final int OVER = Batches.MAX_ROWS + 1;
    private static final Long DEPT_ID = 1L;

    @Mock private CallerScope callerScope;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private AdminAuditService auditService;
    @Mock private StudentManagementService studentService;

    private static User user(UserRole role, String username, String deptCode) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", 1L);
        u.setUsername(username);
        u.setRole(role);
        if (deptCode != null) {
            Department d = new Department();
            d.setId(DEPT_ID);
            d.setCode(deptCode);
            u.setDepartment(d);
        }
        return u;
    }

    private static void assertRefusedAsBadRequest(Throwable t) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException e = (ResponseStatusException) t;
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getReason()).contains("At most " + Batches.MAX_ROWS).contains("you sent " + OVER);
    }

    // ---- subject import ----

    @Mock private SubjectImportService subjectImportService;
    @Mock private SubjectService subjectService;
    @Mock private com.college.backlog.repository.SubjectRepository subjectRepository;
    @InjectMocks private SubjectController subjectController;

    @Test
    void subjectImportRefusesAnOversizedBatchAndImportsNothing() {
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.ADMIN, "admin", null));
        when(departmentRepository.existsById(DEPT_ID)).thenReturn(true);

        SubjectImportRequest req = new SubjectImportRequest();
        req.setDeptId(DEPT_ID);
        req.setAcademicYearOffered(2025);
        req.setRows(IntStream.range(0, OVER).mapToObj(i -> {
            SubjectImportRequest.Row r = new SubjectImportRequest.Row();
            r.setCourseCode("C" + i);
            r.setSubjectName("S" + i);
            r.setSemester(4);
            r.setCredits(3);
            r.setSubjectType("REGULAR");
            r.setEligibleDeptCodes(List.of());
            return r;
        }).toList());

        assertThatThrownBy(() -> subjectController.importSubjects(req, null))
            .satisfies(BatchSizeLimitTest::assertRefusedAsBadRequest);
        verify(subjectImportService, never()).importRows(any(), anyInt(), anyBoolean(), any());
        verifyNoInteractions(auditService);
    }

    // ---- clone apply ----

    @Mock private SubjectCloneService cloneService;
    @InjectMocks private SubjectCloneController cloneController;

    @Test
    void cloneApplyRefusesAnOversizedBatchAndCreatesNothing() {
        Department dept = new Department();
        dept.setId(DEPT_ID);
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.ADMIN, "admin", null));
        when(departmentRepository.findById(DEPT_ID)).thenReturn(Optional.of(dept));

        SubjectCloneApplyRequest req = new SubjectCloneApplyRequest();
        req.setDeptId(DEPT_ID);
        req.setTargetYear(2025);
        List<SubjectCloneApplyRequest.Row> rows = new ArrayList<>();
        for (int i = 0; i < OVER; i++) {
            SubjectCloneApplyRequest.Row r = new SubjectCloneApplyRequest.Row();
            r.setCourseCode("C" + i);
            r.setSubjectName("S" + i);
            r.setSemester(4);
            r.setCredits(3);
            rows.add(r);
        }
        req.setRows(rows);

        assertThatThrownBy(() -> cloneController.apply(req, null))
            .satisfies(BatchSizeLimitTest::assertRefusedAsBadRequest);
        verify(cloneService, never()).apply(any(), anyInt(), any());
        verifyNoInteractions(auditService);
    }

    // ---- student import ----

    @Mock private ProctorScopeService proctorScope;
    @Mock private StudentRepository studentRepository;
    @InjectMocks private StudentManagementController studentController;

    @Test
    void studentImportRefusesAnOversizedBatchAndImportsNothing() {
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.ADMIN, "admin", null));

        StudentImportRequest req = new StudentImportRequest();
        req.setRows(IntStream.range(0, OVER).mapToObj(i -> {
            StudentImportRow r = new StudentImportRow();
            r.setRollNo("1MS24CS" + String.format("%03d", i));
            r.setName("S" + i);
            return r;
        }).toList());

        assertThatThrownBy(() -> studentController.importRows(req, null))
            .satisfies(BatchSizeLimitTest::assertRefusedAsBadRequest);
        // normalizeUsn is the first per-row call, so untouched means the loop never started
        verify(studentService, never()).normalizeUsn(anyString());
        verify(studentService, never()).createStudent(any(), anyString());
    }

    // ---- proctor claim (already had a cap; now shares the constant) ----

    @Mock private ProctorAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private ProctorAssignmentController proctorController;

    @Test
    void proctorClaimRefusesAnOversizedBatchAndAssignsNothing() {
        // a PROCTOR actor resolves to itself, so resolveTargetProctor touches no repository
        when(callerScope.requireActor(any())).thenReturn(user(UserRole.PROCTOR, "proctor-user", "CS"));

        ProctorAssignRequest req = new ProctorAssignRequest();
        req.setRollNos(IntStream.range(0, OVER)
            .mapToObj(i -> "1MS24CS" + String.format("%03d", i)).toList());

        assertThatThrownBy(() -> proctorController.assign(req, null))
            .satisfies(BatchSizeLimitTest::assertRefusedAsBadRequest);
        verify(assignmentRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
