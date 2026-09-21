package com.college.backlog.service;

import com.college.backlog.controller.SubjectCloneController;
import com.college.backlog.controller.dto.SubjectCloneApplyRequest;
import com.college.backlog.controller.dto.SubjectCloneResult;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.AdminAuditEventRepository;
import com.college.backlog.repository.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A failed audit write must not destroy the report of an operation that already committed.
 *
 * <p>Both batch loops commit each row on its own, so by the time the single audit row is written
 * the subjects exist. That write used to escape and 500 the request: the admin was told the whole
 * operation failed when every row had landed, and lost the per-row result table, which is kept
 * nowhere else. The audit row was missing either way — the old behaviour paid that price AND threw
 * away the report.
 *
 * <p>One audit row can never be atomic with N independent commits, so the gap cannot be closed;
 * it can only be disclosed. These tests pin the disclosure.
 */
@ExtendWith(MockitoExtension.class)
class AuditBestEffortTest {

    // ---- the primitive ----

    private final AdminAuditEventRepository repository = mock(AdminAuditEventRepository.class);

    private AdminAuditService serviceWith(AdminAuditEventRepository repo) {
        AdminAuditService service = new AdminAuditService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "repository", repo);
        return service;
    }

    private static User admin() {
        User u = new User();
        u.setUsername("admin");
        u.setRole(UserRole.ADMIN);
        return u;
    }

    @Test
    void aRecordedRowReturnsNoWarning() {
        String warning = serviceWith(repository).recordBestEffort(
                AdminAuditAction.SUBJECT_CLONE, admin(), AuditTargetType.DEPARTMENT, "1", "detail");

        assertThat(warning).isNull();
    }

    /** A Neon connection drop is the realistic failure, and it is NOT a
     *  DataIntegrityViolationException — it is a sibling, which is why the catch is on
     *  RuntimeException rather than that one type. */
    @Test
    void aFailedWriteReturnsTheWarningInsteadOfThrowing() {
        doThrow(new DataAccessResourceFailureException("connection closed"))
                .when(repository).save(any());

        String warning = serviceWith(repository).recordBestEffort(
                AdminAuditAction.SUBJECT_CLONE, admin(), AuditTargetType.DEPARTMENT, "1", "detail");

        assertThat(warning).isEqualTo(AdminAuditService.UNRECORDED_WARNING);
        // it names the thing that matters: the work is not lost
        assertThat(warning).contains("saved");
    }

    // ---- the controller keeps the result ----

    @Mock private CallerScope callerScope;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private SubjectCloneService cloneService;
    @Mock private AdminAuditService auditService;
    @InjectMocks private SubjectCloneController cloneController;

    private SubjectCloneApplyRequest applyRequest() {
        SubjectCloneApplyRequest req = new SubjectCloneApplyRequest();
        req.setDeptId(1L);
        req.setTargetYear(2025);
        req.setRows(List.of());
        return req;
    }

    private void callerIsAdminOfDeptOne() {
        User actor = admin();
        when(callerScope.requireActor(any())).thenReturn(actor);
        Department d = new Department();
        d.setId(1L);
        d.setCode("CS");
        when(departmentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
    }

    /**
     * THE case. The rows are committed; only the audit write failed. The admin must still get the
     * counts and the per-row table — and be told the record is incomplete.
     */
    @Test
    void aFailedCloneAuditStillReturnsTheResultAndWarnsTheAdmin() {
        callerIsAdminOfDeptOne();
        when(cloneService.apply(any(), any(Integer.class), any()))
                .thenReturn(new SubjectCloneResult(3, 37, 0, List.of()));
        when(auditService.recordBestEffort(any(), any(), any(), anyString(), anyString()))
                .thenReturn(AdminAuditService.UNRECORDED_WARNING);

        SubjectCloneResult result = cloneController.apply(applyRequest(), null);

        // the report survives in full — this is what the 500 used to destroy
        assertThat(result.getCreated()).isEqualTo(3);
        assertThat(result.getSkipped()).isEqualTo(37);
        assertThat(result.getWarning()).isEqualTo(AdminAuditService.UNRECORDED_WARNING);
    }

    /** The control: a normal run carries no warning, or the banner would cry wolf every time. */
    @Test
    void aSuccessfulCloneCarriesNoWarning() {
        callerIsAdminOfDeptOne();
        when(cloneService.apply(any(), any(Integer.class), any()))
                .thenReturn(new SubjectCloneResult(3, 0, 0, List.of()));
        when(auditService.recordBestEffort(any(), any(), any(), anyString(), anyString()))
                .thenReturn(null);

        assertThat(cloneController.apply(applyRequest(), null).getWarning()).isNull();
    }
}
