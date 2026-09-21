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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PATCH /api/admin/users/{username}} — renaming somebody else's account.
 *
 * <p>This endpoint is <b>ADMIN only</b>, deliberately narrower than every sibling under
 * {@code /api/admin/users} (which admit ADMIN/PRINCIPAL/HOD) and narrower than SecurityConfig's
 * {@code /api/admin/**} rule. That narrowing is the entire point of the endpoint's authorization,
 * so its {@code @PreAuthorize} is load-bearing rather than decorative: deleting it lets PRINCIPAL
 * and HOD rename accounts, which is exactly what the two cases below catch.
 *
 * <p><b>Mutation-tested</b>, as the authorization suite requires. Removing
 * {@code @PreAuthorize("hasRole('ADMIN')")} from {@code renameUser} fails precisely
 * {@code principalCannotRename} and {@code hodCannotRename} — 403 becomes 200 — and nothing else.
 * A widening mutation was used on purpose: SecurityConfig already admits all five admin roles to
 * {@code /api/admin/**}, so only the annotation narrows, and only removing it can prove that.
 *
 * <p>Renames are asserted by CONTENT as well as status. A denial that still renamed the row would
 * satisfy a 403 assertion while doing the damage, so every denial re-reads the account and asserts
 * the old username is still the one on file.
 *
 * <p>Full {@code @SpringBootTest}, not standalone MockMvc: {@code @EnableMethodSecurity} lives on
 * SecurityConfig, so a standalone setup skips method security entirely and every assertion here
 * would hold no matter what the annotation said. Needs the local Postgres — see
 * BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserRenameAuthorizationTest {

    private static final String USERS = "/api/admin/users";
    private static final String BODY = "{\"newUsername\":\"renamed-account\"}";

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

    // ---- the ladder ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminCanRename() throws Exception {
        rename(DEPT_OFFICE).andExpect(status().isOk());

        assertThat(userRepository.findByUsername("renamed-account")).isPresent();
        assertThat(userRepository.findByUsername(DEPT_OFFICE)).isEmpty();
    }

    /** PRINCIPAL manages HOD/DEPT_OFFICE/PROCTOR everywhere else here, so this is the case that
     *  separates "ADMIN only" from "the usual ladder" — and the first to break if the annotation
     *  is dropped. */
    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalCannotRename() throws Exception {
        rename(DEPT_OFFICE).andExpect(status().isForbidden());
        assertUnchanged(DEPT_OFFICE);
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotRenameEvenWithinTheirOwnDepartment() throws Exception {
        // DEPT_OFFICE is in the HOD's own department, so dept scoping cannot be what denies this —
        // only the role gate can.
        rename(DEPT_OFFICE).andExpect(status().isForbidden());
        assertUnchanged(DEPT_OFFICE);
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotRename() throws Exception {
        rename(PROCTOR).andExpect(status().isForbidden());
        assertUnchanged(PROCTOR);
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotRename() throws Exception {
        rename(DEPT_OFFICE).andExpect(status().isForbidden());
        assertUnchanged(DEPT_OFFICE);
    }

    // ---- refusals that are not about the caller's role ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void renamingYourOwnAccountIsRefusedHere() throws Exception {
        // Self-rename requires the current password, so it lives on POST /api/auth/change-username;
        // allowing it here would be a way around that check.
        rename(ADMIN).andExpect(status().isBadRequest());
        assertUnchanged(ADMIN);
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void renamingToATakenUsernameIsAConflict() throws Exception {
        mockMvc.perform(patch(USERS + "/" + DEPT_OFFICE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUsername\":\"" + HOD + "\"}"))
                .andExpect(status().isConflict());
        assertUnchanged(DEPT_OFFICE);
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void renamingAnUnknownAccountIs404() throws Exception {
        rename("no-such-user").andExpect(status().isNotFound());
    }

    /** The 4-character floor keeps the derived default password (username + "4321") at or above
     *  ChangePasswordRequest's 8-character minimum, so it is a real rule, not cosmetic. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void tooShortAUsernameIsRejected() throws Exception {
        mockMvc.perform(patch(USERS + "/" + DEPT_OFFICE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUsername\":\"ab\"}"))
                .andExpect(status().isBadRequest());
        assertUnchanged(DEPT_OFFICE);
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions rename(String target) throws Exception {
        return mockMvc.perform(patch(USERS + "/" + target).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    /** A denial must not have renamed anything — the status alone cannot show that. */
    private void assertUnchanged(String username) {
        assertThat(userRepository.findByUsername(username)).isPresent();
        assertThat(userRepository.findByUsername("renamed-account")).isEmpty();
    }
}
