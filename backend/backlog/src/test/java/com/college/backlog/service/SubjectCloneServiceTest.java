package com.college.backlog.service;

import com.college.backlog.controller.dto.SubjectCloneApplyRequest;
import com.college.backlog.controller.dto.SubjectCloneResult;
import com.college.backlog.controller.dto.SubjectClonePreviewResponse;
import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;
import com.college.backlog.repository.SubjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectCloneServiceTest {

    @Mock private SubjectRepository subjectRepository;
    @Mock private SubjectService subjectService;
    @InjectMocks private SubjectCloneService service;

    private Subject subject(String name, String code, int sem, int credits) {
        Department d = new Department();
        d.setId(1L);
        Subject s = new Subject();
        s.setSubjectName(name);
        s.setCourseCode(code);
        s.setSemester(sem);
        s.setCredits(credits);
        s.setSubjectType(SubjectType.REGULAR);
        s.setDepartment(d);
        return s;
    }

    @Test
    void previewCopiesCodesVerbatimAndFlagsExisting() {
        // A course keeps its code across years; only academic_year_offered moves, and the
        // (code, year) uniqueness is what separates the two offerings.
        Subject a = subject("Data Structures", "CSL44", 4, 4);
        Subject b = subject("Operating Systems", "CSL45", 4, 3);
        when(subjectRepository
                .findByDepartment_IdAndAcademicYearOfferedAndSemesterInOrderBySemesterAscSubjectNameAsc(eq(1L), eq(2022), any()))
            .thenReturn(List.of(a, b));
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("CSL44", 2023)).thenReturn(false);
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("CSL45", 2023)).thenReturn(true);

        SubjectClonePreviewResponse res = service.preview(1L, 2022, 2023, null);

        assertThat(res.getRows()).hasSize(2);
        assertThat(res.getRows().get(0).getCourseCode()).isEqualTo("CSL44");
        assertThat(res.getRows().get(0).getStatus()).isEqualTo("WOULD_CREATE");
        assertThat(res.getRows().get(1).getStatus()).isEqualTo("WOULD_SKIP");
    }

    @Test
    void previewReportsErrorForASourceWithNoCourseCode() {
        // preview/apply parity: apply refuses a blank code, so preview must not promise a
        // WOULD_CREATE that apply will turn into an ERROR row.
        when(subjectRepository
                .findByDepartment_IdAndAcademicYearOfferedAndSemesterInOrderBySemesterAscSubjectNameAsc(eq(1L), eq(2022), any()))
            .thenReturn(List.of(subject("Orphan", "   ", 4, 4)));

        SubjectClonePreviewResponse res = service.preview(1L, 2022, 2023, null);

        assertThat(res.getRows().get(0).getStatus()).isEqualTo("ERROR");
        verify(subjectRepository, never()).existsByCourseCodeAndAcademicYearOffered(any(), anyInt());
    }

    @Test
    void applyCreatesNewSkipsExistingAndForcesTargetYear() {
        SubjectCloneApplyRequest.Row newRow = new SubjectCloneApplyRequest.Row();
        newRow.setSubjectName("Data Structures");
        newRow.setCourseCode("23CSL44");
        newRow.setSemester(4);
        newRow.setCredits(4);
        SubjectCloneApplyRequest.Row existingRow = new SubjectCloneApplyRequest.Row();
        existingRow.setSubjectName("Operating Systems");
        existingRow.setCourseCode("23CSL45");
        existingRow.setSemester(4);
        existingRow.setCredits(3);

        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("23CSL44", 2023)).thenReturn(false);
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("23CSL45", 2023)).thenReturn(true);

        SubjectCloneResult res = service.apply(1L, 2023, List.of(newRow, existingRow));

        assertThat(res.getCreated()).isEqualTo(1);
        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getErrors()).isZero();

        ArgumentCaptor<SubjectCreateRequest> captor = ArgumentCaptor.forClass(SubjectCreateRequest.class);
        verify(subjectService, times(1)).createSubject(captor.capture());
        assertThat(captor.getValue().getCourseCode()).isEqualTo("23CSL44");
        assertThat(captor.getValue().getAcademicYearOffered()).isEqualTo(2023);
        assertThat(captor.getValue().getDeptId()).isEqualTo(1L);
    }

    private SubjectCloneApplyRequest.Row row(String name, String code, int sem, int credits) {
        SubjectCloneApplyRequest.Row r = new SubjectCloneApplyRequest.Row();
        r.setSubjectName(name);
        r.setCourseCode(code);
        r.setSemester(sem);
        r.setCredits(credits);
        return r;
    }

    /**
     * apply() is deliberately NOT transactional, so rows before a failure are already COMMITTED.
     * An unexpected RuntimeException used to escape the loop and become a request-level 500 —
     * CloneSubjectsTab then never calls setResult, so the admin saw "Apply failed." and no result
     * table, with no way to learn that some subjects now exist in the target year.
     *
     * DataAccessResourceFailureException is the realistic shape: a Neon connection drop mid-batch.
     * It extends RuntimeException but NOT DataIntegrityViolationException, so the pre-existing
     * clauses all miss it. Asserting the LATER row was still attempted is the point — a test that
     * only checked the counts would pass even if the batch aborted after the last row.
     */
    @Test
    void anUnexpectedFailureOnOneRowIsReportedWithoutAbortingTheBatch() {
        SubjectCloneApplyRequest.Row first = row("Data Structures", "23CSL44", 4, 4);
        SubjectCloneApplyRequest.Row rogue = row("Operating Systems", "23CSL45", 4, 3);
        SubjectCloneApplyRequest.Row last = row("Databases", "23CSL46", 4, 3);

        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered(any(), eq(2023)))
            .thenReturn(false);
        // ONE stub matching any(), branching inside — not two argThat stubs. Under Mockito's
        // default STRICT_STUBS an invocation matching no stubbing throws PotentialStubbingProblem,
        // which is a RuntimeException, so the catch-all under test would swallow it and report a
        // spurious ERROR row. That is not a flaw in the fix (its whole job is to catch anything),
        // but it does mean stubbing here has to cover every call the loop makes.
        when(subjectService.createSubject(any())).thenAnswer(inv -> {
            SubjectCreateRequest r = inv.getArgument(0);
            if ("23CSL45".equals(r.getCourseCode())) {
                // Neon drops the connection while the middle row is being written.
                throw new DataAccessResourceFailureException("connection reset");
            }
            return null;
        });

        SubjectCloneResult res = service.apply(1L, 2023, List.of(first, rogue, last));

        assertThat(res.getCreated()).isEqualTo(2);
        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getSkipped()).isZero();
        // the row AFTER the failure was still attempted — i.e. the loop did not abort
        verify(subjectService).createSubject(argThat(r -> "23CSL46".equals(r.getCourseCode())));
        assertThat(res.getRows()).hasSize(3);
        assertThat(res.getRows().get(1).getStatus()).isEqualTo("ERROR");
        // a generic sentence, not the driver's raw text
        assertThat(res.getRows().get(1).getMessage()).isEqualTo("Could not create this subject.");
    }

    /**
     * NumberFormatException extends IllegalArgumentException, so without its own clause it lands in
     * the IAE catch and its raw message is shown as though the ROW were malformed. Latent today —
     * createSubject converts its one IAE source to a ResponseStatusException — but pinned so the
     * clause is not "tidied away" as unreachable.
     */
    @Test
    void aNumberFormatExceptionIsReportedGenerically_notAsARowValidationMessage() {
        SubjectCloneApplyRequest.Row r = row("Data Structures", "23CSL44", 4, 4);
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered(any(), eq(2023)))
            .thenReturn(false);
        doThrow(new NumberFormatException("For input string: \"null\""))
            .when(subjectService).createSubject(any());

        SubjectCloneResult res = service.apply(1L, 2023, List.of(r));

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getRows().get(0).getMessage())
            .as("the driver's raw text must not reach the admin as a row-level reason")
            .isEqualTo("Could not create this subject.")
            .doesNotContain("For input string");
    }

    /** The curated messages this method throws itself must still reach the admin verbatim — the
     *  new generic clause must not swallow them. */
    @Test
    void theMethodsOwnValidationMessagesStillSurface() {
        SubjectCloneResult res = service.apply(1L, 2023, List.of(row("X", "  ", 4, 3)));

        assertThat(res.getErrors()).isEqualTo(1);
        assertThat(res.getRows().get(0).getMessage()).isEqualTo("Course code is required.");
    }
}
