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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role × endpoint matrix for /api/admin/users, split out because the management LADDER is a
 * matrix in its own right. See {@link StudentManagementAuthorizationTest} for why this is a full
 * {@code @SpringBootTest} and why every denial asserts exactly 403.
 *
 * <p>The ladder, from {@code canManageRole}: <b>ADMIN</b> manages any role, <b>PRINCIPAL</b> only
 * {HOD, DEPT_OFFICE, PROCTOR} — <i>not</i> ADMIN — and <b>HOD</b> only {DEPT_OFFICE, PROCTOR}, and
 * only within their own department. DEPT_OFFICE and PROCTOR are absent from every annotation here:
 * staff management is HOD and above.
 *
 * <p>Two properties are easy to get wrong and are asserted as CONTENT, not status:
 * <ol>
 *   <li><b>The list is filtered by the same ladder that guards the writes.</b> A caller who cannot
 *       manage a role must not even see those accounts — otherwise the UI offers a Reset button
 *       that 403s, and, worse, the list itself leaks who the administrators are. A widened filter
 *       still answers 200.</li>
 *   <li><b>Escalation is the risk being tested.</b> "PRINCIPAL creates an ADMIN" and "HOD creates
 *       an HOD" are the two ways a lesser account manufactures a greater one, and neither is
 *       visible to a status-code-only reading of the create endpoint, which answers 201 either way.</li>
 * </ol>
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserManagementAuthorizationTest {

    private static final String USERS = "/api/admin/users";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;

    private AdminAuthorizationFixture.Ids ids;

    @BeforeEach
    void seedTheCast() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
    }

    // ---- roles with no access at all ----

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeIsDeniedEveryUserEndpoint() throws Exception {
        expectDeniedOnEveryEndpoint();
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorIsDeniedEveryUserEndpoint() throws Exception {
        expectDeniedOnEveryEndpoint();
    }

    private void expectDeniedOnEveryEndpoint() throws Exception {
        mockMvc.perform(get(USERS)).andExpect(status().isForbidden());
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newproctor", "PROCTOR", ids.csDeptId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(USERS + "/" + DEPT_OFFICE + "/reset").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(USERS + "/" + PROCTOR).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
    }

    // ---- GET / — the list is filtered by the same ladder ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminSeesEveryOtherAccount() throws Exception {
        mockMvc.perform(get(USERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem(PRINCIPAL)))
                .andExpect(jsonPath("$[*].username", hasItem(HOD)))
                .andExpect(jsonPath("$[*].username", hasItem(PROCTOR_OTHER_DEPT)))
                .andExpect(jsonPath("$[*].username", not(hasItem(ADMIN)))); // self is managed elsewhere
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalSeesDeptRolesButNotAdministrators() throws Exception {
        mockMvc.perform(get(USERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem(HOD)))
                .andExpect(jsonPath("$[*].username", hasItem(DEPT_OFFICE)))
                .andExpect(jsonPath("$[*].username", not(hasItem(ADMIN))));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodSeesOnlyManageableRolesInOwnDepartment() throws Exception {
        mockMvc.perform(get(USERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem(DEPT_OFFICE)))
                .andExpect(jsonPath("$[*].username", hasItem(PROCTOR)))
                // a PROCTOR of the OTHER department — role is manageable, department is not
                .andExpect(jsonPath("$[*].username", not(hasItem(PROCTOR_OTHER_DEPT))))
                .andExpect(jsonPath("$[*].username", not(hasItem(ADMIN))))
                .andExpect(jsonPath("$[*].username", not(hasItem(PRINCIPAL))));
    }

    // ---- POST / — escalation is the risk ----

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCannotCreateAnAdministrator() throws Exception {
        // the escalation that would let a PRINCIPAL mint themselves unrestricted access
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newadmin", "ADMIN", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCreatesAnHodInAnyDepartment() throws Exception {
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newhod", "HOD", ids.cvDeptId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotCreateAPeerHod() throws Exception {
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newhod", "HOD", ids.csDeptId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCreatesAProctorInOwnDepartment() throws Exception {
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newproctor", "PROCTOR", ids.csDeptId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotCreateIntoAnotherDepartment() throws Exception {
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newproctor", "PROCTOR", ids.cvDeptId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminCreatesAnyRole() throws Exception {
        mockMvc.perform(post(USERS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("newadmin", "ADMIN", null)))
                .andExpect(status().isCreated());
    }

    // ---- POST /{username}/reset — installs a known password, so scope matters as much as create ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodResetsAManageableAccountInOwnDepartment() throws Exception {
        mockMvc.perform(post(USERS + "/" + DEPT_OFFICE + "/reset").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotResetAPrincipalsPassword() throws Exception {
        // the password is derived from the username, so a successful reset here is a full takeover
        // of a higher account — not merely an inconvenience
        mockMvc.perform(post(USERS + "/" + PRINCIPAL + "/reset").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotResetAProctorOfAnotherDepartment() throws Exception {
        mockMvc.perform(post(USERS + "/" + PROCTOR_OTHER_DEPT + "/reset").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCannotResetAnAdministrator() throws Exception {
        mockMvc.perform(post(USERS + "/" + ADMIN + "/reset").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void resettingAnUnknownAccountIsA404NotA403() throws Exception {
        mockMvc.perform(post(USERS + "/no-such-user/reset").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---- DELETE /{username} ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodDeletesAManageableAccountInOwnDepartment() throws Exception {
        mockMvc.perform(delete(USERS + "/" + PROCTOR_WITHOUT_STUDENTS).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotDeleteAProctorOfAnotherDepartment() throws Exception {
        mockMvc.perform(delete(USERS + "/" + PROCTOR_OTHER_DEPT).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCannotDeleteAnAdministrator() throws Exception {
        mockMvc.perform(delete(USERS + "/" + ADMIN).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminDeletesALesserAccount() throws Exception {
        mockMvc.perform(delete(USERS + "/" + PRINCIPAL).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void deletingYourOwnAccountIsA400NotA403() throws Exception {
        // 400, not 403: ADMIN may manage this role — the refusal is the self-delete rule, and the
        // distinction proves the role check was cleared
        mockMvc.perform(delete(USERS + "/" + ADMIN).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private static String createBody(String username, String role, Long departmentId) {
        return "{\"username\":\"" + username + "\",\"role\":\"" + role + "\""
                + (departmentId == null ? "" : ",\"departmentId\":" + departmentId) + "}";
    }
}
