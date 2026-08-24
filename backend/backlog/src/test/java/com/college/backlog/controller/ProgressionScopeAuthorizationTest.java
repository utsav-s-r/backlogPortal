package com.college.backlog.controller;

import com.college.backlog.repository.DepartmentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-student scope on /api/admin/progression — part of step 4 of
 * claude-work/notes/role-endpoint-matrix-plan.md. Covers ONLY {@code view} and {@code override}:
 * the four {@code /bulk} endpoints are ADMIN-only and already executed by
 * {@link BulkProgressionAuthorizationTest}, and the plan's non-goals say not to re-assert what an
 * existing suite owns.
 *
 * <p>This controller is the third place the same two-layer student scope appears — dept-pinning by
 * USN branch code, then {@code ProctorScopeService.assertSupervises} on top — after
 * {@link StudentManagementAuthorizationTest} and the proctor assignment surface. It is tested
 * separately rather than assumed from those, because the layering is re-implemented per controller
 * (each keeps its own {@code DEPT_ROLES} set, deliberately: they genuinely differ, and merging them
 * would silently widen or narrow one).
 *
 * <p>The stake is higher than it looks for a "view": the progression timeline is what binds a
 * backlog semester to an academic year, so an override outside scope silently rewrites which year
 * another department's student is recorded against.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProgressionScopeAuthorizationTest {

    private static final String PROGRESSION = "/api/admin/progression";
    private static final String OVERRIDE_BODY = "{\"academicYear\":" + SUBJECT_YEAR + "}";
    /** Semester 3 exists in every fixture student's timeline window (entry 1, current 4). */
    private static final int SEMESTER = 3;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;

    @BeforeEach
    void seedTheCast() {
        AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
    }

    // ---- PROCTOR: assigned students only ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorViewsAndOverridesAnAssignedStudent() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CS_ASSIGNED_A)).andExpect(status().isOk());
        mockMvc.perform(put(overridePath(CS_ASSIGNED_A)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OVERRIDE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotTouchAnUnassignedStudentInOwnDepartment() throws Exception {
        // dept scope passes; assertSupervises is the only thing refusing
        mockMvc.perform(get(PROGRESSION + "/" + CS_UNASSIGNED)).andExpect(status().isForbidden());
        mockMvc.perform(put(overridePath(CS_UNASSIGNED)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OVERRIDE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotTouchAnotherDepartmentsStudent() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CV_STUDENT)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void proctorWithNoAssignmentsReachesNoStudentAtAll() throws Exception {
        // the empty assigned set must fail closed here too — assertSupervises, not a filtered query
        mockMvc.perform(get(PROGRESSION + "/" + CS_ASSIGNED_A)).andExpect(status().isForbidden());
        mockMvc.perform(get(PROGRESSION + "/" + CS_UNASSIGNED)).andExpect(status().isForbidden());
    }

    // ---- dept-scoped roles: own department, no per-student restriction ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodReachesAnyStudentInOwnDepartment() throws Exception {
        // unassigned to any proctor and still reachable: the hard scope is the PROCTOR's alone
        mockMvc.perform(get(PROGRESSION + "/" + CS_UNASSIGNED)).andExpect(status().isOk());
        mockMvc.perform(put(overridePath(CS_UNASSIGNED)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OVERRIDE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotReachAnotherDepartmentsStudent() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CV_STUDENT)).andExpect(status().isForbidden());
        mockMvc.perform(put(overridePath(CV_STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OVERRIDE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeIsScopedTheSameWay() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CS_ASSIGNED_A)).andExpect(status().isOk());
        mockMvc.perform(get(PROGRESSION + "/" + CV_STUDENT)).andExpect(status().isForbidden());
    }

    // ---- unrestricted roles ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminReachesEveryDepartment() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CV_STUDENT)).andExpect(status().isOk());
        mockMvc.perform(put(overridePath(CV_STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OVERRIDE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalReachesEveryDepartmentToo() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CV_STUDENT)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void anUnknownStudentIsA404NotA403() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/1MS24CS999")).andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get(PROGRESSION + "/" + CS_ASSIGNED_A)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = CS_ASSIGNED_A, roles = "STUDENT")
    void aStudentTokenCannotReadItsOwnProgressionHere() throws Exception {
        // the student-facing surface is /api/student/**; this is the admin one
        mockMvc.perform(get(PROGRESSION + "/" + CS_ASSIGNED_A)).andExpect(status().isForbidden());
    }

    private static String overridePath(String rollNo) {
        return PROGRESSION + "/" + rollNo + "/semester/" + SEMESTER;
    }
}
