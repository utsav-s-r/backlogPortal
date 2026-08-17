package com.college.backlog.service;

import com.college.backlog.controller.dto.BulkProgressionRequest;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.ProgressionBatch;
import com.college.backlog.model.ProgressionOutcome;
import com.college.backlog.model.Student;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProgressionBatchRepository;
import com.college.backlog.repository.ProgressionBatchStudentRepository;
import com.college.backlog.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkProgressionServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private ProgressionBatchRepository batchRepository;
    @Mock private ProgressionBatchStudentRepository batchStudentRepository;
    @Mock private ExamCycleRepository examCycleRepository;
    @Mock private StudentManagementService studentService;
    @InjectMocks private BulkProgressionService service;

    @BeforeEach
    void registrationClosedByDefault() {
        lenient().when(examCycleRepository.findByActiveTrue()).thenReturn(Optional.empty());
        lenient().when(studentService.normalizeUsn(any()))
            .thenAnswer(i -> ((String) i.getArgument(0)).trim().toUpperCase());
    }

    private BulkProgressionRequest req(Long expectedCount, String... exclusions) {
        BulkProgressionRequest r = new BulkProgressionRequest();
        r.setExpectedCount(expectedCount);
        r.setExcludeRollNos(List.of(exclusions));
        return r;
    }

    private Student studentWithRoll(String rollNo) {
        Student s = new Student();
        s.setRollNo(rollNo);
        return s;
    }

    private ProgressionBatch stubBatchSave() {
        ProgressionBatch saved = new ProgressionBatch("admin", null, null);
        saved.setId(7L);
        when(batchRepository.save(any(ProgressionBatch.class))).thenReturn(saved);
        return saved;
    }

    // ---- guard 1: registration must be closed ----

    @Test
    void refusesWhileAnExamCycleIsActive() {
        when(examCycleRepository.findByActiveTrue()).thenReturn(Optional.of(new ExamCycle()));

        assertThatThrownBy(() -> service.run(req(5L), "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Close the active exam cycle");
        verify(studentRepository, never()).bulkPromote(any(), any(), any());
    }

    @Test
    void previewAlsoRefusesWhileAnExamCycleIsActive() {
        when(examCycleRepository.findByActiveTrue()).thenReturn(Optional.of(new ExamCycle()));

        assertThatThrownBy(() -> service.preview(req(null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Close the active exam cycle");
    }

    // ---- guard 2: exclusion list must be real students ----

    @Test
    void rejectsTheWholeRunIfAnExcludedUsnIsUnknown() {
        when(studentRepository.findByRollNoInOrderByRollNo(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.run(req(5L, "1ms24cs999"), "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Not students: 1MS24CS999");
        // the whole request dies — a typo'd exclusion must never silently promote a detained student
        verify(studentRepository, never()).bulkPromote(any(), any(), any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    void blankExclusionLinesAreIgnoredNotRejected() {
        when(studentRepository.findByRollNoInOrderByRollNo(any())).thenReturn(List.of(studentWithRoll("1MS24CS001")));
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(3L);
        when(studentRepository.countAtMax(isNull(), isNull(), any())).thenReturn(0L);
        when(studentRepository.findNotPromotableNeedingAttention(isNull(), isNull(), any())).thenReturn(List.of());

        service.preview(req(null, "1ms24cs001", "  ", ""));

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(studentRepository).countPromotable(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue()).containsExactly("1MS24CS001");
    }

    @Test
    void emptyExclusionListBecomesTheSentinelNeverAnEmptyCollection() {
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(3L);
        when(studentRepository.countAtMax(isNull(), isNull(), any())).thenReturn(0L);
        when(studentRepository.findNotPromotableNeedingAttention(isNull(), isNull(), any())).thenReturn(List.of());

        service.preview(req(null));

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(studentRepository).countPromotable(isNull(), isNull(), captor.capture());
        // SQL "NOT IN ()" is a syntax error, so the collection must never reach the query empty
        assertThat(captor.getValue()).isNotEmpty().containsExactly("-");
    }

    // ---- guard 3: expectedCount ----

    @Test
    void commitRequiresAnExpectedCount() {
        assertThatThrownBy(() -> service.run(req(null), "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("expectedCount is required");
        verify(studentRepository, never()).bulkPromote(any(), any(), any());
    }

    @Test
    void staleExpectedCountIsRejected() {
        // the double-click guard: after a successful run the candidate set has changed
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.run(req(120L), "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("changed since your preview");
        verify(studentRepository, never()).bulkPromote(any(), any(), any());
        verify(batchRepository, never()).save(any());
    }

    // ---- guard 4: semester filter parity ----

    @Test
    void oddSemesterFilterIsRejected() {
        BulkProgressionRequest r = req(1L);
        r.setSemester(3);

        assertThatThrownBy(() -> service.run(r, "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("2, 4, 6 or 8");
    }

    @Test
    void nullSemesterFilterMeansEverySemester() {
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(9L);
        when(studentRepository.countAtMax(isNull(), isNull(), any())).thenReturn(0L);
        when(studentRepository.findNotPromotableNeedingAttention(isNull(), isNull(), any())).thenReturn(List.of());

        assertThat(service.preview(req(null)).getPromoteCount()).isEqualTo(9L);
    }

    // ---- the commit itself ----

    @Test
    void auditIsWrittenBeforeTheUpdateSoSemesterFromIsThePreState() {
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(4L);
        stubBatchSave();
        when(studentRepository.insertPromotedAudit(anyLong(), isNull(), isNull(), any())).thenReturn(4);
        when(studentRepository.insertNotPromotedAudit(anyLong(), isNull(), isNull(), any())).thenReturn(2);
        when(studentRepository.bulkPromote(isNull(), isNull(), any())).thenReturn(4);
        when(batchStudentRepository.countByBatchIdAndOutcome(7L, ProgressionOutcome.EXCLUDED_BY_ADMIN))
            .thenReturn(1);

        service.run(req(4L), "admin");

        // ordering is load-bearing: the UPDATE last, or the audit records the post-state
        InOrder order = inOrder(studentRepository);
        order.verify(studentRepository).insertPromotedAudit(eq(7L), isNull(), isNull(), any());
        order.verify(studentRepository).insertNotPromotedAudit(eq(7L), isNull(), isNull(), any());
        order.verify(studentRepository).bulkPromote(isNull(), isNull(), any());
    }

    @Test
    void headerCountsComeFromTheRowsActuallyWritten() {
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(4L);
        ProgressionBatch batch = stubBatchSave();
        when(studentRepository.insertPromotedAudit(anyLong(), isNull(), isNull(), any())).thenReturn(4);
        when(studentRepository.insertNotPromotedAudit(anyLong(), isNull(), isNull(), any())).thenReturn(3);
        when(studentRepository.bulkPromote(isNull(), isNull(), any())).thenReturn(4);
        when(batchStudentRepository.countByBatchIdAndOutcome(7L, ProgressionOutcome.EXCLUDED_BY_ADMIN))
            .thenReturn(1);

        service.run(req(4L), "admin");

        assertThat(batch.getPromotedCount()).isEqualTo(4);
        assertThat(batch.getExcludedCount()).isEqualTo(1);
        assertThat(batch.getSkippedCount()).isEqualTo(2); // 3 not-promoted - 1 excluded
    }

    @Test
    void abortsIfTheAuditAndTheUpdateDisagree() {
        when(studentRepository.countPromotable(isNull(), isNull(), any())).thenReturn(4L);
        stubBatchSave();
        when(studentRepository.insertPromotedAudit(anyLong(), isNull(), isNull(), any())).thenReturn(4);
        when(studentRepository.insertNotPromotedAudit(anyLong(), isNull(), isNull(), any())).thenReturn(0);
        when(studentRepository.bulkPromote(isNull(), isNull(), any())).thenReturn(3); // one row short

        // the audit would be a lie — roll back rather than record a promotion that didn't happen
        assertThatThrownBy(() -> service.run(req(4L), "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("audited 4 but updated 3");
    }
}
