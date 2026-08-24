package com.college.backlog.controller;

import com.college.backlog.model.Department;
import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;

import java.time.LocalDate;

/**
 * The cast every role × endpoint authorization test needs: one account per role, students and
 * subjects in TWO departments, and three proctors — one with assignments, one with none, one in the
 * other department. Shared because the fixture, not the assertions, is the bulk of the work, and
 * because a per-test cast would drift.
 *
 * <p>Three properties here are load-bearing, and dropping any of them makes a passing suite prove
 * much less than it looks:
 * <ol>
 *   <li><b>A real {@code users} row per role.</b> {@code AccountExistenceFilter} 401s an admin
 *       principal whose row is missing, and a 401 satisfies a naive "not 200" assertion while saying
 *       nothing about roles. {@code @WithMockUser} supplies the identity; this supplies the row.</li>
 *   <li><b>A department on every dept-scoped account.</b> Without one,
 *       {@code CallerScope.requireDepartment} 403s "No department assigned" and EVERY case collapses
 *       into that same failure — the dept-scoping cases would pass for the wrong reason.</li>
 *   <li><b>A cross-department control student</b> ({@link #CV_STUDENT}). An endpoint that scopes
 *       correctly and one that returns the whole college are indistinguishable when every fixture
 *       row is in the same department.</li>
 * </ol>
 *
 * <p>{@link #PROCTOR_WITHOUT_STUDENTS} exists for one specific escalation: {@code assignedRollNos}
 * returns {@code null} for a non-proctor (= unrestricted) and a {@code Set} for a proctor, so
 * "unrestricted" and "restricted to these" differ only by null-vs-set. Any coercion of an EMPTY set
 * to null turns the query unfiltered — a 200 with the whole college in it, which no status-code
 * assertion can catch. Same shape as the fail-open in docs/adr/auth-scoping.md.
 *
 * <p>Rows are written with the repositories rather than through StudentManagementService on purpose:
 * {@code createStudent} is {@code REQUIRES_NEW}, so it would commit outside the test's rollback and
 * leave the shared throwaway database dirty. Seeded inside a {@code @Transactional} test, everything
 * here rolls back.
 */
public final class AdminAuthorizationFixture {

    private AdminAuthorizationFixture() {}

    public static final String ADMIN = "admin-user";
    public static final String PRINCIPAL = "principal-user";
    public static final String HOD = "hod-user";
    public static final String DEPT_OFFICE = "dept_office-user";
    /** PROCTOR supervising {@link #CS_ASSIGNED_A} and {@link #CS_ASSIGNED_B}, nothing else. */
    public static final String PROCTOR = "proctor-user";
    /** PROCTOR with an EMPTY assigned set — the escalation case. */
    public static final String PROCTOR_WITHOUT_STUDENTS = "proctor-empty-user";
    /** PROCTOR in the OTHER department, supervising {@link #CV_STUDENT}. Two axes need it: an HOD
     *  may only manage proctors of their own department, and a proctor may not unassign a student
     *  held by someone else. Both are invisible while every proctor sits in one department. */
    public static final String PROCTOR_OTHER_DEPT = "proctor-cv-user";

    public static final String CS_CODE = "CS";
    public static final String CV_CODE = "CV";

    public static final String CS_ASSIGNED_A = "1MS24CS001";
    public static final String CS_ASSIGNED_B = "1MS24CS002";
    /** Same department as the proctor, deliberately NOT assigned: dept scope alone would let this
     *  one through, so it is what separates dept-pinning from the per-student hard scope. */
    public static final String CS_UNASSIGNED = "1MS24CS003";
    /** The other department — no dept-scoped role may reach it. */
    public static final String CV_STUDENT = "1MS24CV001";

    /** Catalog year both fixture subjects are offered in. The course-code prefix must equal
     *  {@code year % 100} (CourseCodes) — "24CS44" for 2024 — so year and code move together. */
    public static final int SUBJECT_YEAR = 2024;
    public static final String CS_SUBJECT_CODE = "24CS44";
    public static final String CV_SUBJECT_CODE = "24CV44";

    /** Ids of the seeded departments and subjects, which are {@code @GeneratedValue} and so cannot
     *  be constants. Returned rather than re-queried, because the dept-scoping assertions turn on
     *  which id is which. */
    public static final class Ids {
        public final Long csDeptId;
        public final Long cvDeptId;
        public final Long csSubjectId;
        public final Long cvSubjectId;

        private Ids(Long csDeptId, Long cvDeptId, Long csSubjectId, Long cvSubjectId) {
            this.csDeptId = csDeptId;
            this.cvDeptId = cvDeptId;
            this.csSubjectId = csSubjectId;
            this.cvSubjectId = cvSubjectId;
        }
    }

    public static Ids seed(UserRepository users, DepartmentRepository departments,
                    StudentRepository students, ProctorAssignmentRepository assignments,
                    SubjectRepository subjects) {
        Department cs = department(departments, CS_CODE, "Computer Science and Engineering");
        Department cv = department(departments, CV_CODE, "Civil Engineering");

        // ADMIN/PRINCIPAL are unrestricted and carry no department by design
        user(users, ADMIN, UserRole.ADMIN, null);
        user(users, PRINCIPAL, UserRole.PRINCIPAL, null);
        user(users, HOD, UserRole.HOD, cs);
        user(users, DEPT_OFFICE, UserRole.DEPT_OFFICE, cs);
        user(users, PROCTOR, UserRole.PROCTOR, cs);
        user(users, PROCTOR_WITHOUT_STUDENTS, UserRole.PROCTOR, cs);
        user(users, PROCTOR_OTHER_DEPT, UserRole.PROCTOR, cv);

        student(students, CS_ASSIGNED_A, CS_CODE);
        student(students, CS_ASSIGNED_B, CS_CODE);
        student(students, CS_UNASSIGNED, CS_CODE);
        student(students, CV_STUDENT, CV_CODE);

        assignments.save(new ProctorAssignment(CS_ASSIGNED_A, PROCTOR, HOD));
        assignments.save(new ProctorAssignment(CS_ASSIGNED_B, PROCTOR, HOD));
        // held by someone else, in the other department — the row a proctor must not be able to
        // unassign and an HOD must not be able to reach
        assignments.save(new ProctorAssignment(CV_STUDENT, PROCTOR_OTHER_DEPT, ADMIN));

        Subject csSubject = subject(subjects, CS_SUBJECT_CODE, "Operating Systems", cs);
        Subject cvSubject = subject(subjects, CV_SUBJECT_CODE, "Structural Analysis", cv);

        return new Ids(cs.getId(), cv.getId(), csSubject.getId(), cvSubject.getId());
    }

    /**
     * Undo {@link #seed} by hand. Only a test that is NOT {@code @Transactional} needs this — the
     * authorization suites roll back instead. Order follows the foreign keys: registrations and
     * assignments reference students, students and subjects reference departments.
     */
    public static void cleanup(UserRepository users, DepartmentRepository departments,
                        StudentRepository students, ProctorAssignmentRepository assignments,
                        SubjectRepository subjects, RegistrationRepository registrations,
                        ExamCycleRepository cycles) {
        registrations.deleteAll();
        assignments.deleteAll();
        students.deleteAll();
        subjects.deleteAll();
        users.deleteAll();
        departments.deleteAll();
        cycles.deleteAll();
    }

    private static Department department(DepartmentRepository repo, String code, String name) {
        Department d = new Department();
        d.setCode(code);
        d.setDeptName(name);
        d.setContactEmail(code.toLowerCase() + "@msrit.edu");
        return repo.save(d);
    }

    private static void user(UserRepository repo, String username, UserRole role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("{noop}irrelevant"); // never authenticated against; @WithMockUser is the identity
        u.setRole(role);
        u.setDepartment(dept);
        repo.save(u);
    }

    private static Subject subject(SubjectRepository repo, String courseCode, String name,
                                   Department dept) {
        Subject s = new Subject();
        s.setCourseCode(courseCode);
        s.setSubjectName(name);
        s.setSemester(4);
        s.setCredits(4);
        s.setAcademicYearOffered(SUBJECT_YEAR);
        s.setDepartment(dept);
        return repo.save(s);
    }

    private static void student(StudentRepository repo, String rollNo, String branchCode) {
        Student s = new Student();
        s.setRollNo(rollNo);
        s.setName("Fixture " + rollNo);
        s.setEmail(rollNo.toLowerCase() + "@msrit.edu");
        s.setBranch(branchCode);
        s.setDateOfBirth(LocalDate.of(2006, 1, 1));
        s.setYearOfJoining(2024);
        s.setCurrentSemester(4); // even; entry 1 is odd — the parity rule in docs/adr/backlog-progression.md
        s.setEntrySemester(1);
        repo.save(s);
    }
}
