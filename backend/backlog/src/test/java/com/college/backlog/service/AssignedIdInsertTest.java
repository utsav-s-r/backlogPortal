package com.college.backlog.service;

import com.college.backlog.controller.dto.StudentCreateRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.Student;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.StudentSemesterTermRepository;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Student} and {@link ProctorAssignment} carry ids we assign (USN / roll number), so without
 * {@code Persistable.isNew()} Spring Data's save() MERGES a new instance into an existing row: a
 * create that lost a race overwrote the student (DOB included), and a claim that lost a race moved
 * the student to the later proctor, both reported as success. These assert the real database
 * refuses the second write and the first row survives, and that loaded rows still update.
 *
 * <p><b>Deliberately NOT {@code @Transactional}</b>: {@code createStudent} runs REQUIRES_NEW and
 * cannot see uncommitted test rows, and a test transaction's shared persistence context would
 * answer from memory instead of reaching the key. Rows are committed under a department code no
 * fixture uses and removed by hand.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssignedIdInsertTest {

    private static final String DEPT_CODE = "QZ";
    private static final String ROLL = "1MS24QZ901";
    private static final String PROCTOR_A = "assigned-id-proctor-a";
    private static final String PROCTOR_B = "assigned-id-proctor-b";

    @Autowired private StudentManagementService studentService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentSemesterTermRepository termRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private Department department;

    @BeforeEach
    void seedDepartment() {
        cleanUp(); // defensive: this class commits, so a failed run could leave rows behind
        Department d = new Department();
        d.setCode(DEPT_CODE);
        d.setDeptName("Assigned Id Fixture");
        d.setContactEmail("qz@msrit.edu");
        department = departmentRepository.save(d);
    }

    @AfterEach
    void cleanUp() {
        assignmentRepository.findById(ROLL).ifPresent(assignmentRepository::delete);
        termRepository.deleteAll(termRepository.findByRollNo(ROLL));
        studentRepository.findById(ROLL).ifPresent(studentRepository::delete);
        userRepository.findByUsername(PROCTOR_A).ifPresent(userRepository::delete);
        userRepository.findByUsername(PROCTOR_B).ifPresent(userRepository::delete);
        departmentRepository.findByCodeIgnoreCase(DEPT_CODE).ifPresent(departmentRepository::delete);
    }

    private StudentCreateRequest create(String name, LocalDate dob) {
        StudentCreateRequest r = new StudentCreateRequest();
        r.setRollNo(ROLL);
        r.setName(name);
        r.setDateOfBirth(dob);
        r.setCurrentSemester(2);
        r.setEntrySemester(1);
        return r;
    }

    private User proctor(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("{noop}irrelevant");
        u.setRole(UserRole.PROCTOR);
        u.setDepartment(department);
        return userRepository.save(u);
    }

    @Test
    void creatingAnExistingUsnFailsOnTheKeyAndLeavesTheStudentUntouched() {
        studentService.createStudent(create("Original Name", LocalDate.of(2006, 1, 1)), "test");

        // the same path a request that lost the existence-check race takes
        assertThatThrownBy(() ->
                studentService.createStudent(create("Intruder", LocalDate.of(2001, 2, 2)), "test"))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
                assertThat(Constraints.isViolationOf(e, Constraints.STUDENT_ROLL_NO)).isTrue());

        Student stored = studentRepository.findByRollNo(ROLL).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Original Name");
        assertThat(stored.getDateOfBirth()).isEqualTo(LocalDate.of(2006, 1, 1));
    }

    @Test
    void aLoadedStudentStillUpdatesThroughSave() {
        // the phone, edit and reset-DOB endpoints all load the row, change it and save() it
        studentService.createStudent(create("Original Name", LocalDate.of(2006, 1, 1)), "test");
        Student loaded = studentRepository.findByRollNo(ROLL).orElseThrow();
        loaded.setName("Renamed");

        studentRepository.save(loaded);

        assertThat(studentRepository.findByRollNo(ROLL).orElseThrow().getName()).isEqualTo("Renamed");
    }

    @Test
    void aLoadedStudentStillDeletes() {
        // SimpleJpaRepository.delete() silently skips an entity reporting isNew(): without the
        // @PostLoad flip the delete endpoint would answer 204 and remove nothing
        studentService.createStudent(create("Original Name", LocalDate.of(2006, 1, 1)), "test");
        Student loaded = studentRepository.findByRollNo(ROLL).orElseThrow();

        studentService.deleteStudent(loaded, "test");

        assertThat(studentRepository.existsById(ROLL)).isFalse();
    }

    @Test
    void aLoadedAssignmentStillDeletes() {
        // the unassign endpoint loads the row and delete()s it; without the @PostLoad flip that
        // delete() is skipped as "new" and the endpoint answers 204 with the student still assigned.
        // The transactional authorization suites cannot see this.
        studentService.createStudent(create("Original Name", LocalDate.of(2006, 1, 1)), "test");
        User holder = proctor(PROCTOR_A);
        assignmentRepository.saveAndFlush(new ProctorAssignment(ROLL, holder.getId(), "test"));
        ProctorAssignment loaded = assignmentRepository.findById(ROLL).orElseThrow();

        assignmentRepository.delete(loaded);

        assertThat(assignmentRepository.existsById(ROLL)).isFalse();
    }

    @Test
    void claimingAnAssignedStudentFailsOnTheKeyAndKeepsTheHolder() {
        studentService.createStudent(create("Original Name", LocalDate.of(2006, 1, 1)), "test");
        User first = proctor(PROCTOR_A);
        User second = proctor(PROCTOR_B);
        assignmentRepository.saveAndFlush(new ProctorAssignment(ROLL, first.getId(), "test"));

        assertThatThrownBy(() ->
                assignmentRepository.saveAndFlush(new ProctorAssignment(ROLL, second.getId(), "test")))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
                assertThat(Constraints.isViolationOf(e, Constraints.PROCTOR_ASSIGNMENT_ROLL_NO)).isTrue();
                // students_pkey is a substring of this name; it must not match
                assertThat(Constraints.isViolationOf(e, Constraints.STUDENT_ROLL_NO)).isFalse();
            });

        assertThat(assignmentRepository.findById(ROLL).orElseThrow().getProctorUserId())
            .isEqualTo(first.getId());
    }
}
