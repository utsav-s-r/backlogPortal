package com.college.backlog.service;

import com.college.backlog.model.Student;
import com.college.backlog.model.StudentSemesterTerm;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.StudentSemesterTermRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgressionServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private StudentSemesterTermRepository termRepository;
    @InjectMocks private ProgressionService service;

    private Student student(String rollNo, int currentSemester) {
        Student s = new Student();
        s.setRollNo(rollNo);
        s.setCurrentSemester(currentSemester);
        return s;
    }

    // ---- input validation: shared by both write paths, reached here via overrideProgression ----

    @Test
    void rejectsInvalidSemester() {
        assertThatThrownBy(() -> service.overrideProgression("1MS24CS191", 0, 2025, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSemesterAboveEightAndNeverWrites() {
        // 8 is the last semester, here and everywhere. Rejected before any write.
        assertThatThrownBy(() -> service.overrideProgression("1MS24CS191", 9, 2025, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 8");
        verify(termRepository, never()).save(any());
    }

    @Test
    void acceptsSemesterEightAsTheLastValidOne() {
        when(studentRepository.findByRollNo("1MS24CS191"))
                .thenReturn(Optional.of(student("1MS24CS191", 7)));
        when(termRepository.findByRollNoAndSemester("1MS24CS191", 8)).thenReturn(Optional.empty());

        service.overrideProgression("1MS24CS191", 8, 2027, "admin");

        verify(termRepository).save(any(StudentSemesterTerm.class));
    }

    @Test
    void rejectsUnknownStudent() {
        when(studentRepository.findByRollNo("1MS24CS191")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.overrideProgression("1MS24CS191", 3, 2025, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- backfillLinear: the seed path, run once at student creation ----

    @Test
    void backfillLinearSeedsMissingSemestersWithDerivedYears() {
        Student s = student("1MS24CS191", 4); // admission 2024 from USN
        when(studentRepository.findByRollNo("1MS24CS191")).thenReturn(Optional.of(s));
        when(termRepository.findByRollNo("1MS24CS191")).thenReturn(List.of()); // no rows yet

        int created = service.backfillLinear("1MS24CS191");

        // the full plan (1..8) is seeded, not just up to currentSemester
        assertThat(created).isEqualTo(8);
        ArgumentCaptor<StudentSemesterTerm> captor = ArgumentCaptor.forClass(StudentSemesterTerm.class);
        verify(termRepository, times(8)).save(captor.capture());
        // regular (entry sem 1): 1,2 -> 2024 ; 3,4 -> 2025 ; 5,6 -> 2026 ; 7,8 -> 2027
        // (admissionYear + floor((k - entrySem)/2))
        assertThat(captor.getAllValues())
                .extracting(StudentSemesterTerm::getSemester, StudentSemesterTerm::getAcademicYear)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 2024),
                        org.assertj.core.groups.Tuple.tuple(2, 2024),
                        org.assertj.core.groups.Tuple.tuple(3, 2025),
                        org.assertj.core.groups.Tuple.tuple(4, 2025),
                        org.assertj.core.groups.Tuple.tuple(5, 2026),
                        org.assertj.core.groups.Tuple.tuple(6, 2026),
                        org.assertj.core.groups.Tuple.tuple(7, 2027),
                        org.assertj.core.groups.Tuple.tuple(8, 2027));
    }

    @Test
    void backfillLinearForLateralEntryStartsAtEntrySemesterAndAnchorsYearThere() {
        Student s = student("1MS24CS191", 6); // admission 2024 from USN
        s.setEntrySemester(3);                // lateral entrant: started at sem 3
        when(studentRepository.findByRollNo("1MS24CS191")).thenReturn(Optional.of(s));
        when(termRepository.findByRollNo("1MS24CS191")).thenReturn(List.of()); // no rows yet

        int created = service.backfillLinear("1MS24CS191");

        // no sems 1-2 (never sat them); entry sem 3 anchors the admission year, plan runs to 8
        assertThat(created).isEqualTo(6);
        ArgumentCaptor<StudentSemesterTerm> captor = ArgumentCaptor.forClass(StudentSemesterTerm.class);
        verify(termRepository, times(6)).save(captor.capture());
        // sem 3,4 -> 2024 ; 5,6 -> 2025 ; 7,8 -> 2026
        assertThat(captor.getAllValues())
                .extracting(StudentSemesterTerm::getSemester, StudentSemesterTerm::getAcademicYear)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3, 2024),
                        org.assertj.core.groups.Tuple.tuple(4, 2024),
                        org.assertj.core.groups.Tuple.tuple(5, 2025),
                        org.assertj.core.groups.Tuple.tuple(6, 2025),
                        org.assertj.core.groups.Tuple.tuple(7, 2026),
                        org.assertj.core.groups.Tuple.tuple(8, 2026));
    }

    @Test
    void backfillLinearPreservesExistingRowsWriteOnce() {
        Student s = student("1MS24CS191", 4); // admission 2024, entry sem 1
        when(studentRepository.findByRollNo("1MS24CS191")).thenReturn(Optional.of(s));
        // sems 1 and 3 already recorded, e.g. hand-corrected after a year-back
        when(termRepository.findByRollNo("1MS24CS191")).thenReturn(List.of(
                new StudentSemesterTerm("1MS24CS191", 1, 2024),
                new StudentSemesterTerm("1MS24CS191", 3, 2026)));

        int created = service.backfillLinear("1MS24CS191");

        // only the six missing rows are written; the existing two are never touched
        assertThat(created).isEqualTo(6);
        ArgumentCaptor<StudentSemesterTerm> captor = ArgumentCaptor.forClass(StudentSemesterTerm.class);
        verify(termRepository, times(6)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(StudentSemesterTerm::getSemester)
                .containsExactly(2, 4, 5, 6, 7, 8);
    }

    // ---- overrideProgression: the correction path, audited, overwrites ----

    @Test
    void overrideCreatesRowWhenAbsent() {
        Student s = student("1MS24CS191", 6);
        when(studentRepository.findByRollNo("1MS24CS191")).thenReturn(Optional.of(s));
        when(termRepository.findByRollNoAndSemester("1MS24CS191", 3)).thenReturn(Optional.empty());

        service.overrideProgression("1MS24CS191", 3, 2025, "admin");

        verify(termRepository).save(any(StudentSemesterTerm.class));
    }

    @Test
    void overrideOverwritesTheStoredYear() {
        when(studentRepository.findByRollNo("1MS24CS191"))
                .thenReturn(Optional.of(student("1MS24CS191", 6)));
        StudentSemesterTerm held = new StudentSemesterTerm("1MS24CS191", 3, 2025);
        when(termRepository.findByRollNoAndSemester("1MS24CS191", 3)).thenReturn(Optional.of(held));

        service.overrideProgression("1MS24CS191", 3, 2026, "admin");

        // the correction path is the ONE writer allowed to replace a recorded year
        assertThat(held.getAcademicYear()).isEqualTo(2026);
        verify(termRepository).save(held);
    }
}
