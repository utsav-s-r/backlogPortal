package com.college.backlog.service;

import com.college.backlog.model.Student;
import com.college.backlog.model.StudentSemesterTerm;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.StudentSemesterTermRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes for "student X studied semester N in academic year Y". Two producers only:
 * {@link #backfillLinear} seeds the full entry..8 timeline at student creation (write-once, onto an
 * empty timeline), and {@link #overrideProgression} corrects one semester (overwrites, audited).
 * See docs/adr/backlog-progression.md.
 */
@Service
public class ProgressionService {

    private static final Logger log = LoggerFactory.getLogger(ProgressionService.class);

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentSemesterTermRepository termRepository;

    /** Correct an existing (or missing) row — OVERWRITES the academic year, unlike the write-once
     *  {@link #backfillLinear}. Audited. Does not touch currentSemester. */
    @Transactional
    public void overrideProgression(String rollNo, int semester, int academicYear, String actor) {
        validate(rollNo, semester, academicYear);
        StudentSemesterTerm term = termRepository.findByRollNoAndSemester(rollNo, semester)
                .orElseGet(() -> new StudentSemesterTerm(rollNo, semester, academicYear));
        int previous = term.getAcademicYear();
        term.setAcademicYear(academicYear);
        termRepository.save(term);
        log.info("PROGRESSION_OVERRIDE actor={} rollNo={} semester={} {} -> {}",
                actor, rollNo, semester, previous, academicYear);
    }

    /**
     * Linear-default seed for one student: assume no detention and stamp every semester from
     * entry through 8 as {@code sem k -> admissionYear + floor((k - entrySem)/2)} — e.g. a 2024
     * intake with entry 1 gets 1-2:2024, 3-4:2025, 5-6:2026, 7-8:2027. A lateral entrant anchors
     * at their entry year; pre-entry semesters are never invented (they never sat them).
     *
     * <p>Seeds the whole plan up front, not just to currentSemester, so the timeline is complete
     * the moment a student is created; currentSemester is untouched. Write-once, so hand-corrected
     * rows (e.g. after a year-back) survive. 8 is the last semester, here and everywhere.
     * Assumes entry at the start of an academic year (odd semester) — the realistic lateral case;
     * anything else is a hand-correct.
     *
     * @return number of rows created
     */
    @Transactional
    public int backfillLinear(String rollNo) {
        Student student = studentRepository.findByRollNo(rollNo).orElse(null);
        if (student == null) {
            throw new IllegalArgumentException("Student not found: " + rollNo);
        }
        int admissionYear = admissionYearFromUsn(rollNo);
        if (admissionYear < 0) {
            throw new IllegalArgumentException("Cannot derive admission year from USN: " + rollNo);
        }
        int entry = Math.max(1, student.getEntrySemester());
        // one query for existing rows, not an exists-probe round trip per semester
        // (bulk backfill multiplies them)
        java.util.Set<Integer> recorded = termRepository.findByRollNo(rollNo).stream()
                .map(StudentSemesterTerm::getSemester)
                .collect(java.util.stream.Collectors.toSet());
        int created = 0;
        for (int sem = entry; sem <= Semesters.MAX; sem++) {
            if (!recorded.contains(sem)) {
                int ay = admissionYear + (sem - entry) / 2;
                termRepository.save(new StudentSemesterTerm(rollNo, sem, ay));
                created++;
            }
        }
        return created;
    }

    /** Range-check a semester + academic year. */
    private void validateSemesterAndYear(int semester, int academicYear) {
        Semesters.assertStudiable(semester);
        AcademicYears.assertInRange(academicYear);
    }

    private Student validate(String rollNo, int semester, int academicYear) {
        validateSemesterAndYear(semester, academicYear);
        return studentRepository.findByRollNo(rollNo)
                .orElseThrow(() -> new IllegalArgumentException("Student not found: " + rollNo));
    }

    /** Admission year from USN 1MS<YY>..., or -1 if malformed. */
    public int admissionYearFromUsn(String rollNo) {
        return Usn.admissionYear(rollNo);
    }
}
