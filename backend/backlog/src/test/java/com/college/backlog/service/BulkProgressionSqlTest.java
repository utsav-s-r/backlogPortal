package com.college.backlog.service;

import com.college.backlog.controller.dto.BulkProgressionRequest;
import com.college.backlog.model.ProgressionBatch;
import com.college.backlog.model.ProgressionOutcome;
import com.college.backlog.model.Student;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProgressionBatchStudentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.StudentSemesterTermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bulk progression WRITE path against a real Postgres. BulkProgressionServiceTest mocks the
 * repository, so it proves the guards and the call ORDER but executes none of the SQL; Cypress
 * stubs every call. Nothing else runs {@code INSERT ... SELECT}, the bulk {@code UPDATE}, or the
 * V9 CHECK constraints — and those are exactly the parts that cannot be reasoned into correctness.
 *
 * <p>Needs the local Postgres (see BacklogApplicationTests). {@code @Transactional} rolls it back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BulkProgressionSqlTest {

    @Autowired private BulkProgressionService service;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProgressionBatchStudentRepository batchStudentRepository;
    @Autowired private StudentSemesterTermRepository termRepository;
    @Autowired private ExamCycleRepository examCycleRepository;

    @BeforeEach
    void closeRegistration() {
        examCycleRepository.deactivateAll();
    }

    private void student(String rollNo, String branch, int current, int entry) {
        Student s = new Student();
        s.setRollNo(rollNo);
        s.setName("Test " + rollNo);
        s.setBranch(branch);
        s.setCurrentSemester(current);
        s.setEntrySemester(entry);
        s.setYearOfJoining(2024);
        s.setDateOfBirth(LocalDate.of(2006, 1, 1));
        studentRepository.save(s);
    }

    /**
     * A cohort covering every outcome the CASE has to classify. Branch codes ZA/ZB are deliberately
     * ones no real department uses: the throwaway container is shared across runs and already holds
     * CS/CV rows, so a real code would make every count depend on leftover data.
     */
    private void seedCohort() {
        student("1MS90ZA001", "ZA", 2, 1); // promotes 2 -> 4
        student("1MS90ZA002", "ZA", 4, 1); // promotes 4 -> 6
        student("1MS90ZA003", "ZA", 6, 3); // promotes 6 -> 8, lateral entry
        student("1MS90ZA004", "ZA", 8, 1); // SKIPPED_AT_MAX
        student("1MS90ZA005", "ZA", 5, 1); // SKIPPED_INVALID_SEMESTER (odd current)
        student("1MS90ZA006", "ZA", 4, 2); // SKIPPED_INVALID_SEMESTER (even entry)
        student("1MS90ZB001", "ZB", 2, 1); // different department
    }

    private BulkProgressionRequest req(Integer sem, String dept, Long expected, String... exclude) {
        BulkProgressionRequest r = new BulkProgressionRequest();
        r.setSemester(sem);
        r.setDeptCode(dept);
        r.setExpectedCount(expected);
        r.setExcludeRollNos(List.of(exclude));
        return r;
    }

    private int semesterOf(String rollNo) {
        return studentRepository.findByRollNo(rollNo).orElseThrow().getCurrentSemester();
    }

    private Map<String, ProgressionOutcome> outcomes(Long batchId) {
        return batchStudentRepository.findByBatchIdOrderByRollNo(batchId,
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream()
                .filter(r -> r.getRollNo().startsWith("1MS90"))
                .collect(Collectors.toMap(r -> r.getRollNo(), r -> r.getOutcome()));
    }

    @Test
    void promotesByTwoAndClassifiesEveryoneElse() {
        seedCohort();
        // preview first, exactly as the UI does — its count feeds the commit
        long expected = service.preview(req(null, "ZA", null, "1MS90ZA002")).getPromoteCount();
        assertThat(expected).isEqualTo(2); // ZA001 and ZA003; ZA002 is excluded

        ProgressionBatch batch = service.run(req(null, "ZA", expected, "1MS90ZA002"), "admin-user");

        assertThat(semesterOf("1MS90ZA001")).isEqualTo(4);
        assertThat(semesterOf("1MS90ZA003")).isEqualTo(8);
        // held back, at the cap, and invalid data all stay exactly where they were
        assertThat(semesterOf("1MS90ZA002")).isEqualTo(4);
        assertThat(semesterOf("1MS90ZA004")).isEqualTo(8);
        assertThat(semesterOf("1MS90ZA005")).isEqualTo(5);
        assertThat(semesterOf("1MS90ZA006")).isEqualTo(4);
        // the dept filter held: a ZB student is untouched by a ZA run
        assertThat(semesterOf("1MS90ZB001")).isEqualTo(2);

        assertThat(outcomes(batch.getId())).containsOnly(
            Map.entry("1MS90ZA001", ProgressionOutcome.PROMOTED),
            Map.entry("1MS90ZA003", ProgressionOutcome.PROMOTED),
            Map.entry("1MS90ZA002", ProgressionOutcome.EXCLUDED_BY_ADMIN),
            Map.entry("1MS90ZA004", ProgressionOutcome.SKIPPED_AT_MAX),
            Map.entry("1MS90ZA005", ProgressionOutcome.SKIPPED_INVALID_SEMESTER),
            Map.entry("1MS90ZA006", ProgressionOutcome.SKIPPED_INVALID_SEMESTER));
        assertThat(batch.getPromotedCount()).isEqualTo(2);
        assertThat(batch.getExcludedCount()).isEqualTo(1);
        assertThat(batch.getSkippedCount()).isEqualTo(3);
    }

    @Test
    void auditCapturesThePreStateNotThePostState() {
        seedCohort();
        ProgressionBatch batch = service.run(req(null, "ZA", 3L), "admin-user");

        // semester_from must be where the student WAS — the UPDATE runs after the audit insert
        batchStudentRepository.findByBatchIdOrderByRollNo(batch.getId(),
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream()
                .filter(r -> r.getRollNo().equals("1MS90ZA001"))
                .forEach(r -> {
                    assertThat(r.getSemesterFrom()).isEqualTo(2);
                    assertThat(r.getSemesterTo()).isEqualTo(4);
                });
        assertThat(semesterOf("1MS90ZA001")).isEqualTo(4);
    }

    @Test
    void semesterFilterNarrowsToThatSemesterOnly() {
        seedCohort();
        service.run(req(4, "ZA", 1L), "admin-user"); // only ZA002 is a promotable sem-4 ZA student

        assertThat(semesterOf("1MS90ZA002")).isEqualTo(6);
        assertThat(semesterOf("1MS90ZA001")).isEqualTo(2); // sem 2, outside the filter
        assertThat(semesterOf("1MS90ZA003")).isEqualTo(6); // sem 6, outside the filter
    }

    @Test
    void neverWritesToTheAcademicYearTimeline() {
        seedCohort();
        long before = termRepository.count();

        service.run(req(null, "ZA", 3L), "admin-user");

        // the load-bearing invariant: bulk progression is NOT a third student_semester_terms writer
        assertThat(termRepository.count()).isEqualTo(before);
    }

    @Test
    void aStaleExpectedCountCannotCommitTwice() {
        seedCohort();
        long expected = service.preview(req(null, "ZA", null)).getPromoteCount();
        service.run(req(null, "ZA", expected), "admin-user");

        // the double-click: same confirmation replayed after a successful run
        assertThatThrownBy(() -> service.run(req(null, "ZA", expected), "admin-user"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("changed since your preview");
        assertThat(semesterOf("1MS90ZA001")).isEqualTo(4); // moved once, not twice
    }

    @Test
    void unknownExclusionFailsBeforeAnythingIsWritten() {
        seedCohort();
        assertThatThrownBy(() -> service.run(req(null, "ZA", 3L, "1MS90ZZ999"), "admin-user"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Not students");
        assertThat(semesterOf("1MS90ZA001")).isEqualTo(2);
    }

    @Test
    void nullFiltersCoverEveryDepartment() {
        seedCohort();
        // exercises the CAST(:param AS ...) IS NULL branch of both filters in the write statements
        long expected = service.preview(req(null, null, null)).getPromoteCount();
        service.run(req(null, null, expected), "admin-user");

        assertThat(semesterOf("1MS90ZB001")).isEqualTo(4); // the ZB student moves this time
    }
}
