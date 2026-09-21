package com.college.backlog.controller;

import com.college.backlog.controller.dto.BatchResult;
import com.college.backlog.controller.dto.ProgressionRowResult;
import com.college.backlog.controller.dto.StudentCreateRequest;
import com.college.backlog.controller.dto.StudentImportRequest;
import com.college.backlog.controller.dto.StudentImportRow;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.service.CallerScope;
import com.college.backlog.service.StudentManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A semester the caller never gave must not be invented.
 *
 * <p>The import used to fall back to entry semester 1 when neither the row nor the batch set one.
 * 1 is a LEGAL entry semester, so it passed every validator and created the student — a lateral
 * entrant silently got an eligibility window starting at semester 1, with
 * {@code seedLinearTimeline} writing write-once timeline rows for semesters they never studied.
 * Current semester fell back to 0, which cannot validate, so that half already failed loudly; the
 * two now behave the same and say which field is missing instead of describing its legal shape.
 *
 * <p>Reachable only through the API — {@code ImportStudentsTab} initialises both selects and
 * offers no blank option — which is precisely why no UI test could have caught it.
 *
 * <p>Plain Mockito, matching {@link BatchRowFailureReportingTest}: the loop is ordinary Java and
 * the controller uses field injection. ADMIN actors keep {@code callerDeptCode()} at null, so no
 * department stubbing is needed. Stubs stay minimal — STRICT_STUBS fails on an unused one.
 */
@ExtendWith(MockitoExtension.class)
class StudentImportSemesterDefaultsTest {

    private static final String ROLL = "1MS24CS001";

    @Mock private CallerScope callerScope;
    @Mock private StudentManagementService studentService;
    @Mock private StudentRepository studentRepository;
    @Mock private com.college.backlog.service.ProctorScopeService proctorScope;
    @Mock private com.college.backlog.repository.DepartmentRepository departmentRepository;
    @InjectMocks private StudentManagementController controller;

    private void callerIsAdmin() {
        User u = new User();
        u.setUsername("admin");
        u.setRole(UserRole.ADMIN);
        when(callerScope.requireActor(any())).thenReturn(u);
        when(studentService.normalizeUsn(anyString())).thenReturn(ROLL);
    }

    private StudentImportRow row(Integer currentSemester, Integer entrySemester) {
        StudentImportRow r = new StudentImportRow();
        r.setRollNo(ROLL);
        r.setName("Asha Rao");
        r.setDateOfBirth(LocalDate.of(2006, 4, 12));
        r.setCurrentSemester(currentSemester);
        r.setEntrySemester(entrySemester);
        return r;
    }

    private StudentImportRequest request(StudentImportRow r, Integer defCurrent, Integer defEntry) {
        StudentImportRequest req = new StudentImportRequest();
        req.setRows(List.of(r));
        req.setDefaultCurrentSemester(defCurrent);
        req.setDefaultEntrySemester(defEntry);
        return req;
    }

    // ---- absent is a failure, not a value ----

    @Test
    void anEntrySemesterNobodySuppliedIsRefusedInsteadOfAssumedToBeOne() {
        callerIsAdmin();

        BatchResult<ProgressionRowResult> res =
                controller.importRows(request(row(2, null), 2, null), null);

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getCreated()).isZero();
        ProgressionRowResult reported = res.getResults().get(0);
        assertThat(reported.getStatus()).isEqualTo("ERROR");
        assertThat(reported.getMessage()).contains("Entry semester is required");
        // the whole point: no student is written under an invented semester
        verify(studentService, never()).createStudent(any(), anyString());
    }

    @Test
    void aCurrentSemesterNobodySuppliedIsRefusedByName() {
        callerIsAdmin();

        BatchResult<ProgressionRowResult> res =
                controller.importRows(request(row(null, 1), null, 1), null);

        ProgressionRowResult reported = res.getResults().get(0);
        assertThat(reported.getStatus()).isEqualTo("ERROR");
        // it always failed; it used to blame the shape ("must be an even semester") of a number
        // the caller never sent
        assertThat(reported.getMessage()).contains("Current semester is required");
        // and the row reports NO semester rather than 0 — there is no semester 0, and
        // BatchResultTable renders null as an em dash
        assertThat(reported.getSemester()).isNull();
        verify(studentService, never()).createStudent(any(), anyString());
    }

    /**
     * A dry run must refuse exactly what the real import refuses, or Preview says WOULD_CREATE for
     * a row Import rejects — the rule {@code validateNewStudent} exists to hold.
     */
    @Test
    void theDryRunRefusesIdentically() {
        callerIsAdmin();
        StudentImportRequest req = request(row(2, null), 2, null);
        req.setDryRun(true);

        BatchResult<ProgressionRowResult> res = controller.importRows(req, null);

        assertThat(res.getResults().get(0).getStatus()).isEqualTo("ERROR");
        assertThat(res.getResults()).noneMatch(r -> "WOULD_CREATE".equals(r.getStatus()));
    }

    // ---- the controls: supplying one still works, and still wins in the right order ----

    @Test
    void theBatchDefaultStillFillsAnEmptyColumn() {
        callerIsAdmin();
        when(studentRepository.existsById(ROLL)).thenReturn(false);

        BatchResult<ProgressionRowResult> res =
                controller.importRows(request(row(null, null), 4, 3), null);

        assertThat(res.getResults().get(0).getStatus()).isEqualTo("CREATED");
        ArgumentCaptor<StudentCreateRequest> sent = ArgumentCaptor.forClass(StudentCreateRequest.class);
        verify(studentService).createStudent(sent.capture(), anyString());
        assertThat(sent.getValue().getCurrentSemester()).isEqualTo(4);
        assertThat(sent.getValue().getEntrySemester()).isEqualTo(3);
    }

    @Test
    void theRowBeatsTheBatchDefault() {
        callerIsAdmin();
        when(studentRepository.existsById(ROLL)).thenReturn(false);

        controller.importRows(request(row(8, 5), 2, 1), null);

        ArgumentCaptor<StudentCreateRequest> sent = ArgumentCaptor.forClass(StudentCreateRequest.class);
        verify(studentService).createStudent(sent.capture(), anyString());
        assertThat(sent.getValue().getCurrentSemester()).isEqualTo(8);
        assertThat(sent.getValue().getEntrySemester()).isEqualTo(5);
    }

    /**
     * A semester the caller actually sent is never treated as absent, however nonsensical: 0 must
     * reach the real validator and come back refused for its SHAPE, not reported as "required".
     * Nothing in the loop reads 0 as a marker — the missing case is null all the way through.
     */
    @Test
    void anExplicitZeroIsPassedThroughToValidationRatherThanCalledMissing() {
        callerIsAdmin();
        when(studentRepository.existsById(ROLL)).thenReturn(false);

        controller.importRows(request(row(0, 0), null, null), null);

        ArgumentCaptor<StudentCreateRequest> validated = ArgumentCaptor.forClass(StudentCreateRequest.class);
        verify(studentService).validateNewStudent(validated.capture());
        assertThat(validated.getValue().getCurrentSemester()).isZero();
        assertThat(validated.getValue().getEntrySemester()).isZero();
    }
}
