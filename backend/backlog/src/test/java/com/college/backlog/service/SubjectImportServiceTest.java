package com.college.backlog.service;

import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.controller.dto.SubjectImportRequest;
import com.college.backlog.controller.dto.BatchResult;
import com.college.backlog.controller.dto.SubjectRowResult;
import com.college.backlog.model.Department;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.SubjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectImportServiceTest {

    private static final int YEAR = 2025;
    private static final Long DEPT_ID = 1L;

    @Mock private SubjectRepository subjectRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private SubjectService subjectService;
    @InjectMocks private SubjectImportService service;

    private static SubjectImportRequest.Row row(String code, String name, Integer sem, Integer credits) {
        SubjectImportRequest.Row r = new SubjectImportRequest.Row();
        r.setCourseCode(code);
        r.setSubjectName(name);
        r.setSemester(sem);
        r.setCredits(credits);
        r.setSubjectType("REGULAR");
        r.setEligibleDeptCodes(List.of());
        return r;
    }

    private BatchResult<SubjectRowResult> run(boolean dryRun, SubjectImportRequest.Row... rows) {
        return service.importRows(DEPT_ID, YEAR, dryRun, List.of(rows));
    }

    @Test
    void createsARowAndStampsTheBatchYearAndDepartment() {
        // no stub: a Mockito List-returning mock answers empty, i.e. "nothing exists yet"
        BatchResult<SubjectRowResult> res = run(false, row("CSL44", "Data Structures", 4, 4));

        assertThat(res.getCreated()).isEqualTo(1);
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("CREATED");

        ArgumentCaptor<SubjectCreateRequest> captor = ArgumentCaptor.forClass(SubjectCreateRequest.class);
        verify(subjectService).createSubject(captor.capture());
        // both come from the request, never from the row — the row has no field for either
        assertThat(captor.getValue().getAcademicYearOffered()).isEqualTo(YEAR);
        assertThat(captor.getValue().getDeptId()).isEqualTo(DEPT_ID);
    }

    @Test
    void skipsARowThatAlreadyExistsForTheYear() {
        when(subjectRepository.findExistingCourseCodes(eq(YEAR), any())).thenReturn(List.of("CSL44"));

        BatchResult<SubjectRowResult> res = run(false, row("CSL44", "Data Structures", 4, 4));

        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("SKIPPED_EXISTS");
        verify(subjectService, never()).createSubject(any());
    }

    @Test
    void aDryRunWritesNothingAndReportsWhatWouldHappen() {
        when(subjectRepository.findExistingCourseCodes(eq(YEAR), any())).thenReturn(List.of("CSL45"));

        BatchResult<SubjectRowResult> res = run(true,
            row("CSL44", "Data Structures", 4, 4),
            row("CSL45", "Operating Systems", 4, 3));

        assertThat(res.isDryRun()).isTrue();
        assertThat(res.getResults().get(0).getStatus()).isEqualTo("WOULD_CREATE");
        assertThat(res.getResults().get(1).getStatus()).isEqualTo("WOULD_SKIP");
        verify(subjectService, never()).createSubject(any());
    }

    @Test
    void aDryRunStillReportsAnUnknownDepartmentCode() {
        // preview/apply parity: a preview that stayed silent here would promise a WOULD_CREATE the
        // real run turns into an ERROR — the break already fixed on the progression import
        // findAll answers empty, so no department code resolves

        SubjectImportRequest.Row elective = row("CSL55", "Machine Learning", 5, 3);
        elective.setSubjectType("ELECTIVE");
        elective.setEligibleDeptCodes(List.of("ZZ"));

        BatchResult<SubjectRowResult> res = run(true, elective);

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getResults().get(0).getMessage()).contains("Unknown department code 'ZZ'");
    }

    @Test
    void resolvesEligibleDepartmentCodesToIds() {
        Department cv = new Department();
        cv.setId(7L);
        cv.setCode("CV");
        when(departmentRepository.findAll()).thenReturn(List.of(cv));

        SubjectImportRequest.Row elective = row("CSL55", "Machine Learning", 5, 3);
        elective.setSubjectType("ELECTIVE");
        elective.setEligibleDeptCodes(List.of("CV"));

        run(false, elective);

        ArgumentCaptor<SubjectCreateRequest> captor = ArgumentCaptor.forClass(SubjectCreateRequest.class);
        verify(subjectService).createSubject(captor.capture());
        assertThat(captor.getValue().getEligibleDeptIds()).containsExactly(7L);
    }

    @Test
    void refusesAnElectiveThatNamesNoDepartments() {
        // an elective with an empty list is registrable by NOBODY and reports nothing anywhere
        SubjectImportRequest.Row elective = row("CSL55", "Machine Learning", 5, 3);
        elective.setSubjectType("ELECTIVE");
        elective.setEligibleDeptCodes(List.of());

        BatchResult<SubjectRowResult> res = run(false, elective);

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getResults().get(0).getMessage()).contains("at least one eligible department");
        verify(subjectService, never()).createSubject(any());
    }

    @Test
    void refusesAnUnknownSubjectTypeRatherThanDefaultingToRegular() {
        // "ELECTIV" silently becoming REGULAR creates a subject the elective's students never see
        SubjectImportRequest.Row bad = row("CSL55", "Machine Learning", 5, 3);
        bad.setSubjectType("ELECTIV");

        BatchResult<SubjectRowResult> res = run(false, bad);

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getResults().get(0).getMessage()).contains("Unknown subject type");
        verify(subjectService, never()).createSubject(any());
    }

    @Test
    void reportsMissingAndOutOfRangeFieldsPerRow() {
        BatchResult<SubjectRowResult> res = run(false,
            row("", "Data Structures", 4, 4),
            row("CSL44", "  ", 4, 4),
            row("CSL45", "Operating Systems", null, 4),
            row("CSL46", "Databases", 9, 4),
            row("CSL47", "Networks", 4, null));

        assertThat(res.getErrors()).isEqualTo(5);
        assertThat(res.getResults()).extracting("message")
            .anyMatch(m -> String.valueOf(m).contains("Course code is required"))
            .anyMatch(m -> String.valueOf(m).contains("Subject name is required"))
            .anyMatch(m -> String.valueOf(m).contains("Semester is required"))
            .anyMatch(m -> String.valueOf(m).contains("Credits are required"));
        verify(subjectService, never()).createSubject(any());
    }

    @Test
    void keepsTheReasonFromAServiceRefusal() {
        // ResponseStatusException must be caught ABOVE the generic RuntimeException clause, or its
        // actionable getReason() is flattened into the generic sentence
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Academic year 2025 is out of range."))
            .when(subjectService).createSubject(any());

        BatchResult<SubjectRowResult> res = run(false, row("CSL44", "Data Structures", 4, 4));

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getResults().get(0).getMessage()).isEqualTo("Academic year 2025 is out of range.");
    }

    @Test
    void anUnexpectedFailureBecomesAnErrorRowAndDoesNotAbortTheBatch() {
        // The batch is NOT transactional, so rows before this one are already committed. A
        // DataAccessResourceFailureException (a Neon connection drop) extends RuntimeException but
        // NOT DataIntegrityViolationException, so only the catch-all sees it. Without that clause
        // the loop aborts, the request 500s, and the record of what was actually written is lost.
        //
        // One any() matcher branching inside, not several argThats: Mockito's STRICT_STUBS throws
        // PotentialStubbingProblem — itself a RuntimeException — on an unmatched invocation, which
        // the catch-all would swallow into a spurious ERROR row and make this test pass vacuously.
        doThrow(new DataAccessResourceFailureException("connection reset"))
            .when(subjectService).createSubject(any());

        BatchResult<SubjectRowResult> res = run(false,
            row("CSL44", "Data Structures", 4, 4),
            row("CSL45", "Operating Systems", 4, 3));

        assertThat(res.getErrors()).isEqualTo(2);
        assertThat(res.getResults()).hasSize(2);
        assertThat(res.getResults().get(1).getCourseCode()).isEqualTo("CSL45");
    }

    @Test
    void resolvesBothLookupsOncePerBATCH_notOncePerRow() {
        // Neon is remote, so round trips dominate. Asserting "once regardless of row count" rather
        // than a magic number: a per-row regression gets "fixed" by bumping a constant, which is how
        // an N+1 gets waved through.
        Department cv = new Department();
        cv.setId(7L);
        cv.setCode("CV");
        when(departmentRepository.findAll()).thenReturn(List.of(cv));

        run(true,
            row("CSL44", "Data Structures", 4, 4),
            row("CSL45", "Operating Systems", 4, 3),
            row("CSL46", "Databases", 4, 3),
            row("CSL47", "Networks", 4, 3));

        verify(subjectRepository, times(1)).findExistingCourseCodes(eq(YEAR), any());
        verify(departmentRepository, times(1)).findAll();
        verify(subjectRepository, never()).existsByCourseCodeAndAcademicYearOffered(anyString(), anyInt());
    }

    @Test
    void handlesANullRowList() {
        BatchResult<SubjectRowResult> res = service.importRows(DEPT_ID, YEAR, false, null);

        assertThat(res.getCreated()).isZero();
        assertThat(res.getResults()).isEmpty();
    }
}
