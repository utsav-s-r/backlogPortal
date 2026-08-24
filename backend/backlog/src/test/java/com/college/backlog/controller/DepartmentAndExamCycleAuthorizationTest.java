package com.college.backlog.controller;

import com.college.backlog.model.ExamCycle;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
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

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role × endpoint matrix for the two college-wide resources — departments
 * ({@link AdminController}) and exam cycles ({@link ExamCycleController}) — part of step 4 of
 * claude-work/notes/role-endpoint-matrix-plan.md. Grouped because they share one shape that none of
 * the earlier controllers had: <b>reads are open to every admin role while writes are narrow, and
 * neither resource has a department to scope by</b>, so the role check is the ONLY control. There is
 * no second layer to fall back on.
 *
 * <p>Exam cycles carry the sharper history. The class-level annotation is {@code hasRole('ADMIN')}
 * with the read endpoint widening itself — deliberately the narrow default, because the previous
 * shape was the inverse and that is <b>how DEPT_OFFICE ended up able to close registration
 * college-wide</b> (see the comment on ExamCycleController). Activating or deactivating a cycle
 * opens or closes registration for every department at once, so these assertions are pinning a
 * real past incident, not a hypothetical.
 *
 * <p>Department deletes are guarded twice over: the role check, then a 409 if anything still
 * references the department. Both are asserted, and the 409 doubles as proof the caller cleared
 * authorization.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DepartmentAndExamCycleAuthorizationTest {

    private static final String DEPARTMENTS = "/api/admin/departments";
    private static final String EXAM_CYCLES = "/api/admin/exam-cycles";
    private static final String DEPT_BODY =
            "{\"deptName\":\"Mechanical Engineering\",\"code\":\"ME\",\"contactEmail\":\"me@msrit.edu\"}";
    private static final String CYCLE_BODY = "{\"name\":\"Supplementary 2026\",\"examMonthYear\":\"2026-06\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;

    private AdminAuthorizationFixture.Ids ids;
    private Long cycleId;

    @BeforeEach
    void seedTheCast() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
        // an inactive cycle to activate/deactivate; deactivateAll first so a cycle left active in
        // the shared throwaway database cannot change what these endpoints do
        examCycleRepository.deactivateAll();
        cycleId = examCycleRepository.save(new ExamCycle("Fixture Cycle", "2026-01")).getId();
    }

    // ---- departments: reads open, writes ADMIN/PRINCIPAL only ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void everyAdminRoleIncludingProctorMayReadDepartments() throws Exception {
        mockMvc.perform(get(DEPARTMENTS)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodMayReadButNotWriteDepartments() throws Exception {
        // HOD is dept-scoped everywhere else in the app; here they are simply read-only, NOT
        // "pinned to their own department" — an easy thing to get wrong when adding an endpoint
        mockMvc.perform(get(DEPARTMENTS)).andExpect(status().isOk());
        expectDepartmentWritesDenied();
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeMayNotWriteDepartments() throws Exception {
        expectDepartmentWritesDenied();
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorMayNotWriteDepartments() throws Exception {
        expectDepartmentWritesDenied();
    }

    private void expectDepartmentWritesDenied() throws Exception {
        mockMvc.perform(post(DEPARTMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DEPT_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(DEPARTMENTS + "/" + ids.csDeptId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DEPT_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(DEPARTMENTS + "/" + ids.csDeptId).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminCreatesADepartment() throws Exception {
        mockMvc.perform(post(DEPARTMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DEPT_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCreatesADepartmentToo() throws Exception {
        mockMvc.perform(post(DEPARTMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DEPT_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void deletingAReferencedDepartmentIsA409NotA403() throws Exception {
        // the CS department has users, students and a subject — 409 proves ADMIN cleared the role
        // check and was stopped by the reference guard instead
        mockMvc.perform(delete(DEPARTMENTS + "/" + ids.csDeptId).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void deletingAnUnknownDepartmentIsA404NotA403() throws Exception {
        mockMvc.perform(delete(DEPARTMENTS + "/999999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---- exam cycles: read open to all five, every write ADMIN-only ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void everyAdminRoleIncludingProctorMayReadExamCycles() throws Exception {
        // the registrations page's cycle filter is built from this list
        mockMvc.perform(get(EXAM_CYCLES)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCannotOpenOrCloseRegistration() throws Exception {
        // PRINCIPAL is the highest non-ADMIN role and still denied: registration state is
        // college-wide, so it is ADMIN's alone
        expectExamCycleWritesDenied();
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotOpenOrCloseRegistration() throws Exception {
        expectExamCycleWritesDenied();
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotOpenOrCloseRegistration() throws Exception {
        // THE regression this pins: DEPT_OFFICE could once close registration for every department
        expectExamCycleWritesDenied();
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotOpenOrCloseRegistration() throws Exception {
        expectExamCycleWritesDenied();
    }

    private void expectExamCycleWritesDenied() throws Exception {
        mockMvc.perform(post(EXAM_CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CYCLE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(EXAM_CYCLES + "/" + cycleId + "/activate").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(EXAM_CYCLES + "/" + cycleId + "/deactivate").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminCreatesActivatesAndDeactivatesACycle() throws Exception {
        mockMvc.perform(post(EXAM_CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CYCLE_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(put(EXAM_CYCLES + "/" + cycleId + "/activate").with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(put(EXAM_CYCLES + "/" + cycleId + "/deactivate").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void activatingAnUnknownCycleIsA404NotA403() throws Exception {
        mockMvc.perform(put(EXAM_CYCLES + "/999999/activate").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get(EXAM_CYCLES)).andExpect(status().isUnauthorized());
    }
}
