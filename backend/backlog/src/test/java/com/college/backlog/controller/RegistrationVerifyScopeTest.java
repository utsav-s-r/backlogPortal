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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may VERIFY or REJECT a registration — the electronic form of the HOD signature on the
 * printed form. Nothing covered this before.
 *
 * <p>The rule changed on 2026-09-20: it is the STUDENT's department, not the subject's. Scoping by
 * subject made the authority multi-valued (a registration spanning three departments could be
 * actioned by any of them, first click winning) and let a CSE HOD sign off a Civil student's form,
 * while that student's own HOD could lose sight of it entirely when an elective's eligibility list
 * was edited. VIEWING deliberately stayed wider — every involved department sees the row, which
 * {@link RegistrationReadAuthorizationTest} covers.
 *
 * <p>PROCTOR is unchanged and needs no branch case: assignment refuses a roll number outside the
 * proctor's own department (ProctorAssignmentController), so a supervised student is always one of
 * theirs.
 *
 * <p>Full context, not standalone MockMvc: {@code @PreAuthorize} and {@code AccountExistenceFilter}
 * live in the filter chain, and the scope check itself reads a real {@code users} row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationVerifyScopeTest {

    private static final String VERIFY = "/api/register/verify/";
    private static final String CS_STUDENTS_REG = "REG-VERIFY-CS";
    private static final String CV_STUDENTS_REG = "REG-VERIFY-CV";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;

    @BeforeEach
    void seed() {
        Ids ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository,
                studentRepository, assignmentRepository, subjectRepository);
        examCycleRepository.deactivateAll();
        ExamCycle cycle = examCycleRepository.save(new ExamCycle("Verify Scope Cycle", "2026-01"));
        Subject csSubject = subjectRepository.findById(ids.csSubjectId).orElseThrow();

        // Both registrations carry a CS-owned subject. That is the point: under the old
        // subject-based rule the CS HOD could action BOTH, including the Civil student's.
        registration(CS_STUDENTS_REG, CS_ASSIGNED_A, csSubject, cycle);
        registration(CV_STUDENTS_REG, CV_STUDENT, csSubject, cycle);
    }

    private void registration(String regId, String rollNo, Subject subject, ExamCycle cycle) {
        Student student = studentRepository.findByRollNo(rollNo).orElseThrow();
        Registration r = new Registration();
        r.setRegId(regId);
        r.setStudent(student);
        r.setSubjects(List.of(subject));
        r.setExamCycle(cycle);
        r.setStatus(RegistrationStatus.SUBMITTED);
        r.setRegisteredAt(Instant.now());
        r.setSnapName(student.getName());
        r.setSnapBranch(student.getBranch());
        r.setSnapSemester(student.getCurrentSemester());
        r.setSnapYearOfJoining(student.getYearOfJoining());
        registrationRepository.save(r);
    }

    private org.springframework.test.web.servlet.ResultActions verify(String regId) throws Exception {
        return mockMvc.perform(put(VERIFY + regId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"VERIFIED\"}"));
    }

    private RegistrationStatus statusOf(String regId) {
        return registrationRepository.findByRegId(regId).orElseThrow().getStatus();
    }

    // ---- the student's department may act ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodVerifiesItsOwnStudentsRegistration() throws Exception {
        verify(CS_STUDENTS_REG).andExpect(status().isOk());
        assertThat(statusOf(CS_STUDENTS_REG)).isEqualTo(RegistrationStatus.VERIFIED);
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeVerifiesItsOwnStudentsRegistration() throws Exception {
        verify(CS_STUDENTS_REG).andExpect(status().isOk());
    }

    // ---- another department may not, however involved its subjects are ----

    /** The case the change exists for. A CS-owned subject is on this registration, so the old rule
     *  allowed it — the CSE HOD could sign off a Civil student's form. */
    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotVerifyAnotherDepartmentsStudentEvenOnItsOwnSubject() throws Exception {
        verify(CV_STUDENTS_REG).andExpect(status().isForbidden());
        // Status, not just the code: a 403 that still flipped the row would be the worse bug.
        assertThat(statusOf(CV_STUDENTS_REG)).isEqualTo(RegistrationStatus.SUBMITTED);
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotVerifyAnotherDepartmentsStudent() throws Exception {
        verify(CV_STUDENTS_REG).andExpect(status().isForbidden());
        assertThat(statusOf(CV_STUDENTS_REG)).isEqualTo(RegistrationStatus.SUBMITTED);
    }

    // ---- proctor: by student, unchanged ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorVerifiesAnAssignedStudent() throws Exception {
        verify(CS_STUDENTS_REG).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void proctorWithNoAssignmentsVerifiesNothing() throws Exception {
        verify(CS_STUDENTS_REG).andExpect(status().isForbidden());
        assertThat(statusOf(CS_STUDENTS_REG)).isEqualTo(RegistrationStatus.SUBMITTED);
    }

    /** The other-department proctor supervises the CV student, so the branch rule and the proctor
     *  rule agree here — the assignment could not have crossed departments in the first place. */
    @Test
    @WithMockUser(username = PROCTOR_OTHER_DEPT, roles = "PROCTOR")
    void theOtherDepartmentsProctorVerifiesItsOwnAssignedStudent() throws Exception {
        verify(CV_STUDENTS_REG).andExpect(status().isOk());
    }

    // ---- unrestricted roles ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminVerifiesAnyDepartmentsStudent() throws Exception {
        verify(CV_STUDENTS_REG).andExpect(status().isOk());
    }
}
