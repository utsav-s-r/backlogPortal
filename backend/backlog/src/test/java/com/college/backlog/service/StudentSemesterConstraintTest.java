package com.college.backlog.service;

import com.college.backlog.controller.dto.StudentCreateRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.Student;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The semester rules, at the level that actually guarantees them (V8).
 *
 * <p>They were application-only: {@code StudentManagementService.validateSemesters}, reached by
 * create, edit and CSV import alike. That is why no invalid row exists — but it held because
 * three call sites remembered to ask, and two open issues (a {@code current_semester} of 0
 * promoting to 2 as PROMOTED; a semester-8 student with entry 9 classified one way by the
 * progression preview and another by its audit) described exactly what a fourth, forgetful write
 * path would produce. Both are now impossible rather than merely unproduced.
 *
 * <p>Order matters and is asserted: the SERVICE must refuse first, with a readable message, so a
 * user sees "Current semester must be even" rather than the constraint's generic 409. The
 * constraint is the backstop for code that never asks.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StudentSemesterConstraintTest {

    private static final String ROLL = "1MS24CS777";

    @Autowired private StudentRepository studentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentManagementService studentService;

    @BeforeEach
    void seedTheDepartment() {
        if (departmentRepository.findByCodeIgnoreCase("CS").isEmpty()) {
            Department cs = new Department();
            cs.setCode("CS");
            cs.setDeptName("Computer Science");
            departmentRepository.saveAndFlush(cs);
        }
    }

    private Student student(int currentSemester, int entrySemester) {
        Student s = new Student();
        s.setRollNo(ROLL);
        s.setName("Constraint Student");
        s.setEmail(ROLL.toLowerCase() + "@msrit.edu");
        s.setBranch("CS");
        s.setDateOfBirth(LocalDate.of(2006, 1, 1));
        s.setYearOfJoining(2024);
        s.setCurrentSemester(currentSemester);
        s.setEntrySemester(entrySemester);
        return s;
    }

    // ---- the database refuses, whatever the caller forgot ----

    /**
     * Written through the repository on purpose: this is the write path a future feature would
     * take if it skipped the service, and the one the two issues were really about.
     */
    @ParameterizedTest(name = "current={0}, entry={1}")
    @CsvSource({
        "0, 1",   // the #2 case: even and below 8, so the progression SQL would promote it to 2
        "9, 1",   // above MAX
        "5, 1",   // odd current
        "4, 2",   // even entry
        "4, 9",   // the #13 case: entry outside 1..8 entirely
        "2, 3",   // entry after current
    })
    void theDatabaseRefusesAnInvalidSemesterPairEvenWithoutTheService(int current, int entry) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> studentRepository.saveAndFlush(student(current, entry)));
    }

    @ParameterizedTest(name = "current={0}, entry={1}")
    @CsvSource({"2, 1", "4, 1", "4, 3", "8, 7", "8, 1"})
    void theDatabaseAcceptsEveryLegitimatePair(int current, int entry) {
        assertThatCode(() -> studentRepository.saveAndFlush(student(current, entry)))
                .doesNotThrowAnyException();
    }

    // ---- the service still answers first, and readably ----

    /**
     * Without this the constraint would be a regression in disguise: the same bad input would
     * reach the database and come back as GlobalExceptionHandler's generic "conflicts with
     * existing data" 409 instead of a message naming the field.
     */
    @Test
    void theServiceRefusesFirstWithAMessageNamingTheRule() {
        StudentCreateRequest req = new StudentCreateRequest();
        req.setRollNo(ROLL);
        req.setName("Constraint Student");
        req.setDateOfBirth(LocalDate.of(2006, 1, 1));
        req.setCurrentSemester(0);
        req.setEntrySemester(1);

        IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                () -> studentService.validateNewStudent(req));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).containsIgnoringCase("semester");
        // and nothing was written on the way to finding out
        assertThat(studentRepository.existsById(ROLL)).isFalse();
    }

    @Test
    void theServiceRefusesAnEntryAfterTheCurrentSemester() {
        IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                () -> studentService.validateSemesters(2, 3));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).contains("cannot be after");
    }
}
