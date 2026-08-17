package com.college.backlog.controller;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ExamCycleRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bulk progression is ADMIN-only, and this is the ONLY test that proves it. The Cypress spec can
 * merely show the tab is hidden — every spec stubs its API calls, so it can never observe a 403 —
 * and BulkProgressionServiceTest mocks the repository, never reaching {@code @PreAuthorize}. UI-only
 * gating is the recurring failure here, so the server check needs its own test.
 *
 * <p>Two things make this test real rather than vacuous, and both are easy to break:
 * <ol>
 *   <li><b>Real {@code users} rows.</b> {@code AccountExistenceFilter} 401s any admin principal with
 *       no row, and a 401 would pass a naive "not 200" assertion while proving nothing about roles.
 *       So each role is seeded and the non-ADMIN expectation is <b>exactly 403</b>, never "not 2xx".</li>
 *   <li><b>The full context.</b> {@code @EnableMethodSecurity} lives on SecurityConfig, so a
 *       standalone MockMvc setup (the GlobalExceptionHandlerTest pattern) would skip the filter
 *       chain and method security entirely and pass no matter what the annotation said.</li>
 * </ol>
 *
 * <p>The ADMIN case doubles as the only automated execution of the bulk native SQL: it runs
 * {@code countPromotable} / {@code findNotPromotable} against a real Postgres, which is what
 * exercises the {@code CAST(:param AS integer) IS NULL} idiom and the {@code NOT (...)}
 * classification predicate. Mockito cannot.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line. {@code @Transactional}
 * rolls the seeded users back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BulkProgressionAuthorizationTest {

    private static final String PREVIEW = "/api/admin/progression/bulk/preview";
    private static final String RUN = "/api/admin/progression/bulk";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ExamCycleRepository examCycleRepository;

    @BeforeEach
    void closeRegistrationAndSeedOneAccountPerRole() {
        // The ADMIN cases must reach the role check's far side, and assertRegistrationClosed() runs
        // first — an active cycle left in the shared throwaway DB would 409 every one of them and
        // silently turn "ADMIN is allowed" into an untested claim. Rolled back with the test.
        examCycleRepository.deactivateAll();
        for (UserRole role : UserRole.values()) {
            User u = new User();
            u.setUsername(usernameFor(role));
            u.setPassword("{noop}irrelevant"); // never authenticated against; @WithMockUser supplies identity
            u.setRole(role);
            userRepository.save(u);
        }
    }

    private static String usernameFor(UserRole role) {
        return role.name().toLowerCase() + "-user";
    }

    // ---- denied: every role except ADMIN ----

    @Test
    @WithMockUser(username = "principal-user", roles = "PRINCIPAL")
    void principalIsDeniedPreview() throws Exception {
        expectForbidden(PREVIEW);
    }

    @Test
    @WithMockUser(username = "principal-user", roles = "PRINCIPAL")
    void principalIsDeniedTheRun() throws Exception {
        expectForbidden(RUN);
    }

    @Test
    @WithMockUser(username = "hod-user", roles = "HOD")
    void hodIsDenied() throws Exception {
        expectForbidden(PREVIEW);
        expectForbidden(RUN);
    }

    @Test
    @WithMockUser(username = "dept_office-user", roles = "DEPT_OFFICE")
    void deptOfficeIsDenied() throws Exception {
        expectForbidden(PREVIEW);
        expectForbidden(RUN);
    }

    @Test
    @WithMockUser(username = "proctor-user", roles = "PROCTOR")
    void proctorIsDenied() throws Exception {
        expectForbidden(PREVIEW);
        expectForbidden(RUN);
    }

    @Test
    void anonymousIsRejected() throws Exception {
        // not 403 — there is no identity to deny, so the chain answers 401
        mockMvc.perform(post(PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- allowed: ADMIN ----

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void adminReachesThePreviewAndTheNativeSqlRuns() throws Exception {
        // 200 means the request passed @PreAuthorize AND the native queries executed against real
        // Postgres — a SQL-level mistake (the CAST idiom, the NOT(...) predicate) surfaces as a 500
        mockMvc.perform(post(PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void adminPreviewAcceptsBothFiltersTogether() throws Exception {
        // exercises the non-null branch of both CAST(...) IS NULL filters in one statement
        mockMvc.perform(post(PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"semester\":4,\"deptCode\":\"CS\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void adminIsPastAuthorizationAndHitsValidationInstead() throws Exception {
        // 400 (not 403) proves ADMIN cleared the role check and reached the service's own guard —
        // committing without a preview's expectedCount
        mockMvc.perform(post(RUN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void adminOddSemesterFilterIsA400NotA403() throws Exception {
        mockMvc.perform(post(PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"semester\":3}"))
                .andExpect(status().isBadRequest());
    }

    // ---- the read side: history + batch detail ----

    @Test
    @WithMockUser(username = "hod-user", roles = "HOD")
    void historyAndDetailAreDeniedToNonAdmins() throws Exception {
        // these leak roll numbers across EVERY department with no dept filter, so a missing
        // annotation here is a cross-department read, not just a write-permission slip
        mockMvc.perform(get(RUN)).andExpect(status().isForbidden());
        mockMvc.perform(get(RUN + "/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "principal-user", roles = "PRINCIPAL")
    void historyIsDeniedToPrincipalToo() throws Exception {
        mockMvc.perform(get(RUN)).andExpect(status().isForbidden());
        mockMvc.perform(get(RUN + "/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void adminReadsTheHistory() throws Exception {
        mockMvc.perform(get(RUN)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void adminGetsA404ForAnUnknownBatchNotA403() throws Exception {
        // id in the PATH -> 404, and it proves ADMIN cleared the role check
        mockMvc.perform(get(RUN + "/999999")).andExpect(status().isNotFound());
    }

    private void expectForbidden(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
