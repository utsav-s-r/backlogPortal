package com.college.backlog.controller;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role × endpoint matrix for the registration READ surface on {@link AdminController}. Split
 * into its own class because it is the only one needing registrations, and those cannot go in the
 * shared {@link AdminAuthorizationFixture}: a registration makes its student and its subject
 * <b>undeletable</b> (409), which would break the delete cases in
 * {@link StudentManagementAuthorizationTest} and {@link SubjectCatalogAuthorizationTest}. So they
 * are seeded here, on top of the shared cast.
 *
 * <p>Two things are specific to this surface:
 * <ol>
 *   <li><b>Department scope here follows the SUBJECT, not the student.</b> A dept-scoped caller may
 *       read a registration when one of its subjects belongs to (or lists as eligible) their
 *       department — {@code assertRegistrationInScope}. Scoping by the student's branch would be a
 *       different, wrong rule, so the fixture pairs each student with their own department's
 *       subject to keep the two from being conflated.</li>
 *   <li><b>The counts endpoint is the escalation twin of the list.</b> A proctor with no assigned
 *       students must get zero counts, not the college totals — and a stat card showing the true
 *       total is a disclosure even though no row is listed. Both endpoints short-circuit the empty
 *       set separately, so both are asserted separately: fixing one and not the other is exactly
 *       the kind of half-fix this suite exists to catch.</li>
 * </ol>
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationReadAuthorizationTest {

    private static final String REGISTRATIONS = "/api/admin/registrations";
    private static final String COUNTS = REGISTRATIONS + "/summary-counts";

    private static final String REG_ASSIGNED = "REG-CS-ASSIGNED";
    private static final String REG_UNASSIGNED = "REG-CS-UNASSIGNED";
    private static final String REG_OTHER_DEPT = "REG-CV";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;

    @BeforeEach
    void seedTheCastAndItsRegistrations() {
        AdminAuthorizationFixture.Ids ids = AdminAuthorizationFixture.seed(
                userRepository, departmentRepository, studentRepository, assignmentRepository,
                subjectRepository);
        examCycleRepository.deactivateAll();
        ExamCycle cycle = examCycleRepository.save(new ExamCycle("Fixture Cycle", "2026-01"));

        Subject csSubject = subjectRepository.findById(ids.csSubjectId).orElseThrow();
        Subject cvSubject = subjectRepository.findById(ids.cvSubjectId).orElseThrow();
        registration(REG_ASSIGNED, CS_ASSIGNED_A, csSubject, cycle);
        registration(REG_UNASSIGNED, CS_UNASSIGNED, csSubject, cycle);
        registration(REG_OTHER_DEPT, CV_STUDENT, cvSubject, cycle);
    }

    private void registration(String regId, String rollNo, Subject subject, ExamCycle cycle) {
        Student student = studentRepository.findByRollNo(rollNo).orElseThrow();
        Registration r = new Registration();
        r.setRegId(regId);
        r.setStudent(student);
        r.setSubjects(List.of(subject));
        r.setExamCycle(cycle);
        r.setStatus(RegistrationStatus.SUBMITTED);
        r.setRegisteredAt(LocalDateTime.now());
        // the snapshot columns are NOT NULL — a registration is immutable history, so it carries
        // its own copy of the student rather than reading through the association
        r.setSnapName(student.getName());
        r.setSnapEmail(student.getEmail());
        r.setSnapBranch(student.getBranch());
        r.setSnapSemester(student.getCurrentSemester());
        r.setSnapYearOfJoining(student.getYearOfJoining());
        registrationRepository.save(r);
    }

    // ---- GET /registrations — scope is content ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminListsEveryDepartment() throws Exception {
        mockMvc.perform(get(REGISTRATIONS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].regId",
                        containsInAnyOrder(REG_ASSIGNED, REG_UNASSIGNED, REG_OTHER_DEPT)));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodListsOwnDepartmentOnly() throws Exception {
        mockMvc.perform(get(REGISTRATIONS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].regId", hasItem(REG_UNASSIGNED)))
                .andExpect(jsonPath("$.content[*].regId", not(hasItem(REG_OTHER_DEPT))));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodAskingForAnotherDepartmentStillGetsOnlyItsOwn() throws Exception {
        // the departmentId parameter is a convenience for the unpinned roles and must never
        // redirect a pinned one — effectiveDeptId puts the caller's own department first
        mockMvc.perform(get(REGISTRATIONS).param("size", "200").param("departmentId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].regId", hasItem(REG_UNASSIGNED)))
                .andExpect(jsonPath("$.content[*].regId", not(hasItem(REG_OTHER_DEPT))));
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeListsOwnDepartmentOnly() throws Exception {
        mockMvc.perform(get(REGISTRATIONS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].regId", not(hasItem(REG_OTHER_DEPT))));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorListsOnlyAssignedStudentsRegistrations() throws Exception {
        // REG_UNASSIGNED is the same department AND the same subject — only the assignment scope
        // separates them
        mockMvc.perform(get(REGISTRATIONS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].regId", containsInAnyOrder(REG_ASSIGNED)));
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void proctorWithNoAssignmentsListsNothing() throws Exception {
        mockMvc.perform(get(REGISTRATIONS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---- GET /registrations/summary-counts — the same scope, counted ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminCountsEveryDepartment() throws Exception {
        mockMvc.perform(get(COUNTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.submitted").value(3));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCountsOwnDepartmentOnly() throws Exception {
        // 2 of the 3, not 3 — a count is a disclosure even when no row is listed
        mockMvc.perform(get(COUNTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCountsOnlyAssignedStudents() throws Exception {
        mockMvc.perform(get(COUNTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void proctorWithNoAssignmentsCountsZeroNotTheCollegeTotal() throws Exception {
        // the counts endpoint short-circuits the empty set SEPARATELY from the list, so fixing one
        // and not the other would leave the totals leaking while the table looked correctly empty
        mockMvc.perform(get(COUNTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.submitted").value(0));
    }

    // ---- GET /registrations/{regId}/events — per-registration scope ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodReadsAnEventTrailInOwnDepartment() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/" + REG_UNASSIGNED + "/events"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotReadAnotherDepartmentsEventTrail() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/" + REG_OTHER_DEPT + "/events"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotReadAnotherDepartmentsEventTrail() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/" + REG_OTHER_DEPT + "/events"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorReadsOnlyAnAssignedStudentsEventTrail() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/" + REG_ASSIGNED + "/events"))
                .andExpect(status().isOk());
        mockMvc.perform(get(REGISTRATIONS + "/" + REG_UNASSIGNED + "/events"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminReadsAnyEventTrail() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/" + REG_OTHER_DEPT + "/events"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void anUnknownRegistrationIsA404NotA403() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/NO-SUCH-REG/events"))
                .andExpect(status().isNotFound());
    }

    // ---- the surrounding read endpoints ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void everyAdminRoleIncludingProctorMayReadTheSubjectFilter() throws Exception {
        mockMvc.perform(get("/api/admin/subjects-for-filter")).andExpect(status().isOk());
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get(REGISTRATIONS)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = CS_ASSIGNED_A, roles = "STUDENT")
    void aStudentTokenCannotReadTheAdminRegistrationList() throws Exception {
        mockMvc.perform(get(REGISTRATIONS)).andExpect(status().isForbidden());
    }
}
