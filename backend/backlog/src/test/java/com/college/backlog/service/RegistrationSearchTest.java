package com.college.backlog.service;

import com.college.backlog.controller.AdminAuthorizationFixture;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin list's free-text search, against real Postgres — the LIKE semantics under test are
 * the database's, so an in-memory or mocked check would prove nothing.
 *
 * <p>Two defects, fixed together 2026-09-21. The term's own wildcards were not escaped, so "50%"
 * matched every row starting with 50 and "A_B" matched "AxB" (wrong results, not injection — the
 * pattern was always a bound parameter). And the predicate read the LIVE student name while the
 * table renders the snapshot, so a renamed student did not match the name on screen. Both names
 * are matched now: swapping would have made them unfindable by their current name instead.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RegistrationSearchTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;

    private AdminAuthorizationFixture.Ids ids;
    private ExamCycle cycle;

    @BeforeEach
    void seed() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
        cycle = examCycleRepository.save(new ExamCycle("Search Cycle", "2026-01"));
    }

    /** @param snapName the name the TABLE shows; the live student row keeps the fixture's. */
    private void registrationFor(String regId, String rollNo, String snapName) {
        Student student = studentRepository.findByRollNo(rollNo).orElseThrow();
        Subject subject = subjectRepository.findById(ids.csSubjectId).orElseThrow();
        Registration reg = new Registration();
        reg.setRegId(regId);
        reg.setStudent(student);
        reg.setSubjects(List.of(subject));
        reg.setExamCycle(cycle);
        reg.setStatus(RegistrationStatus.VERIFIED); // not SUBMITTED: uq_pending_reg_per_cycle
        reg.setRegisteredAt(Instant.now());
        reg.setSnapName(snapName);
        reg.setSnapBranch(student.getBranch());
        reg.setSnapSemester(student.getCurrentSemester());
        reg.setSnapYearOfJoining(student.getYearOfJoining());
        registrationRepository.saveAndFlush(reg);
    }

    private List<String> search(String query) {
        return registrationRepository.findAll(
                        RegistrationSpecification.builder().searchQuery(query).build(),
                        PageRequest.of(0, 50))
                .map(Registration::getRegId)
                .getContent();
    }

    // ---- wildcards in the term are literal ----

    @Test
    void aPercentInTheTermMatchesOnlyALiteralPercent() {
        registrationFor("REG-LITERAL", CS_ASSIGNED_A, "50% Scholarship Holder");
        registrationFor("REG-OTHER", CS_ASSIGNED_B, "Ordinary Student");

        // Unescaped this is LIKE '%50%%' — every row whose name contains "50".
        assertThat(search("50%")).containsExactly("REG-LITERAL");
        // A bare "%" used to match the whole table. Now it is a literal, so it finds the one row
        // whose name CONTAINS a percent sign — asserting emptiness here would be wrong.
        assertThat(search("%")).containsExactly("REG-LITERAL");
    }

    @Test
    void anUnderscoreInTheTermMatchesOnlyALiteralUnderscore() {
        registrationFor("REG-UNDERSCORE", CS_ASSIGNED_A, "A_B Student");
        registrationFor("REG-ANY", CS_ASSIGNED_B, "AxB Student");

        assertThat(search("a_b")).containsExactly("REG-UNDERSCORE");
    }

    /** The escape character itself must survive being escaped, or the pattern is malformed. */
    @Test
    void aBackslashInTheTermIsHarmless() {
        registrationFor("REG-BACKSLASH", CS_ASSIGNED_A, "Back\\Slash");

        assertThat(search("back\\slash")).containsExactly("REG-BACKSLASH");
    }

    // ---- which name is matched ----

    @Test
    void matchesTheSnapshotNameTheTableActuallyShows() {
        // Registered under her married name; the student row has since been corrected.
        registrationFor("REG-SNAPSHOT", CS_ASSIGNED_A, "Asha Menon");

        assertThat(search("menon")).containsExactly("REG-SNAPSHOT");
    }

    /** Kept, not replaced: the office searches the name it knows, which may be the current one. */
    @Test
    void stillMatchesTheCurrentStudentName() {
        registrationFor("REG-LIVE", CS_ASSIGNED_A, "Some Older Name");
        String liveName = studentRepository.findByRollNo(CS_ASSIGNED_A).orElseThrow().getName();

        assertThat(search(liveName)).containsExactly("REG-LIVE");
    }

    @Test
    void stillMatchesTheRollNumber() {
        registrationFor("REG-ROLL", CS_ASSIGNED_A, "Whoever");

        assertThat(search(CS_ASSIGNED_A.toLowerCase())).containsExactly("REG-ROLL");
        assertThat(search("1ms24cs")).isNotEmpty();  // partial, case-insensitive
    }

    @Test
    void matchesNothingWhenTheTermIsInNoField() {
        registrationFor("REG-NONE", CS_ASSIGNED_A, "Asha Rao");

        assertThat(search("zzzz-no-such-student")).isEmpty();
    }
}
