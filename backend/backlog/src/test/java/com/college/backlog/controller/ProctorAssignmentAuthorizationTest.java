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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role × endpoint matrix for /api/admin/proctor — step 2 of
 * claude-work/notes/role-endpoint-matrix-plan.md, sharing {@link AdminAuthorizationFixture} with
 * {@link StudentManagementAuthorizationTest}. Read that class's javadoc for why this is a full
 * {@code @SpringBootTest} and why every denial asserts exactly 403.
 *
 * <p>Two axes are specific to this controller, and neither exists on the student roster:
 * <ol>
 *   <li><b>DEPT_OFFICE is absent from the class-level {@code @PreAuthorize} on purpose</b> —
 *       proctor management is HOD and above. That annotation is the ONLY thing denying them:
 *       SecurityConfig's {@code /api/admin/**} rule admits DEPT_OFFICE, so widening the annotation
 *       (or deleting it) grants a role that no other layer would stop.
 *       {@code EndpointAuthorizationInventoryTest} passes either way, because a rule still exists.</li>
 *   <li><b>The scoped object is the TARGET PROCTOR, not the student.</b> {@code resolveTargetProctor}
 *       decides whose assignment list is read or written: a PROCTOR may only ever act on themselves
 *       (naming anyone else is 403), an HOD only on proctors of their own department, ADMIN and
 *       PRINCIPAL on any. A widening there hands one proctor another's roster — and the request
 *       still answers 200, so only the target-scoping assertions below can see it.</li>
 * </ol>
 *
 * <p>Unlike the student controller, the happy paths here CAN assert their real success status: no
 * service on this path is {@code REQUIRES_NEW}, so every write joins this test's transaction and
 * rolls back.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProctorAssignmentAuthorizationTest {

    private static final String CLAIMABLE = "/api/admin/proctor/claimable";
    private static final String ASSIGNED = "/api/admin/proctor/students";
    private static final String ASSIGNMENTS = "/api/admin/proctor/assignments";

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

    // ---- DEPT_OFFICE: denied on all four, and only the class annotation says so ----

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeIsDeniedEveryProctorEndpoint() throws Exception {
        mockMvc.perform(get(CLAIMABLE)).andExpect(status().isForbidden());
        mockMvc.perform(get(ASSIGNED)).andExpect(status().isForbidden());
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claimOf(CS_UNASSIGNED)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CS_ASSIGNED_A).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get(CLAIMABLE)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = CS_ASSIGNED_A, roles = "STUDENT")
    void aStudentTokenCannotReachTheProctorSurface() throws Exception {
        mockMvc.perform(get(CLAIMABLE)).andExpect(status().isForbidden());
    }

    // ---- PROCTOR: self only, on every endpoint that names a target ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorReadsOwnClaimPickerScopedToOwnDepartment() throws Exception {
        // the picker is the one window a proctor gets onto students outside their set, so its
        // department bound is the whole protection — the Civil student must not appear
        mockMvc.perform(get(CLAIMABLE).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].rollNo", hasItem(CS_UNASSIGNED)))
                .andExpect(jsonPath("$.content[*].rollNo", not(hasItem(CV_STUDENT))));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotOpenAnotherProctorsClaimPicker() throws Exception {
        mockMvc.perform(get(CLAIMABLE).param("proctor", PROCTOR_WITHOUT_STUDENTS))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorReadsOwnAssignedList() throws Exception {
        mockMvc.perform(get(ASSIGNED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].rollNo", containsInAnyOrder(CS_ASSIGNED_A, CS_ASSIGNED_B)));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotReadAnotherProctorsAssignedList() throws Exception {
        // naming another proctor must not silently fall back to "self" either — 403, not 200
        mockMvc.perform(get(ASSIGNED).param("proctor", PROCTOR_OTHER_DEPT))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void proctorWithNoAssignmentsReadsAnEmptyList() throws Exception {
        mockMvc.perform(get(ASSIGNED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorSelfClaimsAnUnclaimedStudent() throws Exception {
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claimOf(CS_UNASSIGNED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.results[0].status").value("CREATED"));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotClaimOnBehalfOfAnotherProctor() throws Exception {
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proctor\":\"" + PROCTOR_WITHOUT_STUDENTS + "\",\"rollNos\":[\""
                                + CS_UNASSIGNED + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotClaimOutsideOwnDepartment() throws Exception {
        // per-row refusal inside a 200 batch: the status says nothing, the row does
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claimOf(CV_STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.results[0].status").value("ERROR"));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void reclaimingOwnStudentIsSkippedNotDuplicated() throws Exception {
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claimOf(CS_ASSIGNED_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("SKIPPED_EXISTS"));
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void claimingAStudentHeldByAnotherProctorIsRefusedPerRow() throws Exception {
        // one proctor per student: the roll_no PK is the guarantee, and a silent steal would be the
        // worst outcome — the row names the current holder instead
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claimOf(CS_ASSIGNED_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.errors").value(1));
    }

    // ---- HOD: own department's proctors, and must name one ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodMustNameATargetProctorAndIsNotDeniedForOmittingOne() throws Exception {
        // 400, not 403: HOD cleared authorization and hit the "a target proctor is required" guard
        mockMvc.perform(get(ASSIGNED)).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodManagesAProctorOfOwnDepartment() throws Exception {
        mockMvc.perform(get(ASSIGNED).param("proctor", PROCTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].rollNo", containsInAnyOrder(CS_ASSIGNED_A, CS_ASSIGNED_B)));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotManageAProctorOfAnotherDepartment() throws Exception {
        mockMvc.perform(get(ASSIGNED).param("proctor", PROCTOR_OTHER_DEPT))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(CLAIMABLE).param("proctor", PROCTOR_OTHER_DEPT))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proctor\":\"" + PROCTOR_OTHER_DEPT + "\",\"rollNos\":[\""
                                + CV_STUDENT + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void namingANonProctorAccountIsA400NotA403() throws Exception {
        // the distinction matters: 403 would read as "not allowed to manage that proctor" and send
        // an HOD hunting for a permission problem that isn't there
        mockMvc.perform(get(ASSIGNED).param("proctor", DEPT_OFFICE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void namingAnUnknownAccountIsA404() throws Exception {
        mockMvc.perform(get(ASSIGNED).param("proctor", "no-such-user"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodAssignsToAProctorOfOwnDepartment() throws Exception {
        mockMvc.perform(post(ASSIGNMENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proctor\":\"" + PROCTOR_WITHOUT_STUDENTS + "\",\"rollNos\":[\""
                                + CS_UNASSIGNED + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
    }

    // ---- ADMIN / PRINCIPAL: any proctor, either department ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminManagesAProctorInAnyDepartment() throws Exception {
        mockMvc.perform(get(ASSIGNED).param("proctor", PROCTOR_OTHER_DEPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].rollNo", hasItem(CV_STUDENT)));
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalManagesAProctorInAnyDepartment() throws Exception {
        // PRINCIPAL is on this controller's annotation (unlike registration verification, where it
        // is deliberately absent) — pinned here so the difference stays a decision, not a drift
        mockMvc.perform(get(ASSIGNED).param("proctor", PROCTOR_OTHER_DEPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].rollNo", hasItem(CV_STUDENT)));
    }

    // ---- DELETE /assignments/{rollNo} — unassign ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorUnassignsOwnStudent() throws Exception {
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CS_ASSIGNED_A).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotUnassignAnotherProctorsStudent() throws Exception {
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CV_STUDENT).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodUnassignsInOwnDepartment() throws Exception {
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CS_ASSIGNED_B).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotUnassignAcrossDepartments() throws Exception {
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CV_STUDENT).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminUnassignsInAnyDepartment() throws Exception {
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CV_STUDENT).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void unassigningAStudentWithNoAssignmentIsA404NotA403() throws Exception {
        // id in the PATH -> 404, and it proves ADMIN cleared the role check
        mockMvc.perform(delete(ASSIGNMENTS + "/" + CS_UNASSIGNED).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private static String claimOf(String rollNo) {
        return "{\"rollNos\":[\"" + rollNo + "\"]}";
    }
}
