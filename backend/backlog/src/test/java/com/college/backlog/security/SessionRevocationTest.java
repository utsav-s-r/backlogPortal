package com.college.backlog.security;

import com.college.backlog.model.Department;
import com.college.backlog.model.Student;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session revocation, driven with REAL tokens rather than {@code @WithMockUser} — the check being
 * proven compares the token's {@code iat} against the account's {@code session_valid_from}, and a
 * mock user carries no token at all (which is exactly why the filter skips it for them).
 *
 * <p>Two fail-opens, one mechanism (V7):
 * <ul>
 *   <li><b>Username reuse revived a dead session with the NEW account's scope.</b> The filter
 *       checked that a row with that username existed and carried the token's role — both true
 *       again after a delete-and-recreate, or after the old name is freed by a rename and handed
 *       to someone else. The old token then resolved to a DIFFERENT department's account.</li>
 *   <li><b>Changing a credential left every existing session alive</b> for up to the full hour:
 *       a password change, an admin reset, or a student's date-of-birth reset.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SessionRevocationTest {

    private static final String VICTIM = "revocation-hod";
    private static final String ADMIN = "revocation-admin";
    private static final String STUDENT = "1MS24RV001";
    private static final String ANY_ADMIN_ENDPOINT = "/api/admin/departments";
    private static final String ANY_STUDENT_ENDPOINT = "/api/student/me";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Department cse;
    private Department civil;

    @BeforeEach
    void seed() {
        cse = department("CS", "Computer Science");
        civil = department("CV", "Civil Engineering");
        staff(ADMIN, UserRole.ADMIN, null);
        staff(VICTIM, UserRole.HOD, cse);
        student(STUDENT);
    }

    private Department department(String code, String name) {
        return departmentRepository.findByCodeIgnoreCase(code).orElseGet(() -> {
            Department d = new Department();
            d.setCode(code);
            d.setDeptName(name);
            return departmentRepository.saveAndFlush(d);
        });
    }

    private User staff(String username, UserRole role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("current-password"));
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.saveAndFlush(u);
    }

    private void student(String rollNo) {
        Student s = new Student();
        s.setRollNo(rollNo);
        s.setName("Revocation Student");
        s.setBranch("CS");
        s.setDateOfBirth(LocalDate.of(2006, 1, 1));
        s.setYearOfJoining(2024);
        s.setCurrentSemester(4);
        s.setEntrySemester(1);
        studentRepository.saveAndFlush(s);
    }

    private Cookie adminCookie(String username, UserRole role) {
        return new Cookie(SessionCookieService.ADMIN_COOKIE,
                jwtService.generateToken(username, role.name()));
    }

    private Cookie studentCookie(String rollNo) {
        return new Cookie(SessionCookieService.STUDENT_COOKIE,
                jwtService.generateToken(rollNo, "STUDENT"));
    }

    // ---- the control: revocation must not break ordinary sessions ----

    @Test
    void anUntouchedAccountsTokenKeepsWorking() throws Exception {
        mockMvc.perform(get(ANY_ADMIN_ENDPOINT).cookie(adminCookie(VICTIM, UserRole.HOD)))
                .andExpect(status().isOk());
    }

    @Test
    void anUntouchedStudentsTokenKeepsWorking() throws Exception {
        mockMvc.perform(get(ANY_STUDENT_ENDPOINT).cookie(studentCookie(STUDENT)))
                .andExpect(status().isOk());
    }

    // ---- #1: a reused username must not revive the old session ----

    /**
     * The reported repro. The old token's subject and role both match the NEW account, so before
     * V7 it resolved — to Civil's department rather than the CSE one it was issued for.
     */
    @Test
    void aTokenIsRefusedWhenItsUsernameNowBelongsToADifferentAccount() throws Exception {
        Cookie oldSession = adminCookie(VICTIM, UserRole.HOD);
        userRepository.delete(userRepository.findByUsername(VICTIM).orElseThrow());
        userRepository.flush();
        staff(VICTIM, UserRole.HOD, civil); // same name, same role, other department

        mockMvc.perform(get(ANY_ADMIN_ENDPOINT).cookie(oldSession))
                .andExpect(status().isUnauthorized());
    }

    /** A token minted for the NEW account is fine — the rule is "older than the account", not
     *  "this username was ever reused". */
    @Test
    void theNewAccountsOwnTokenWorks() throws Exception {
        userRepository.delete(userRepository.findByUsername(VICTIM).orElseThrow());
        userRepository.flush();
        staff(VICTIM, UserRole.HOD, civil);

        mockMvc.perform(get(ANY_ADMIN_ENDPOINT).cookie(adminCookie(VICTIM, UserRole.HOD)))
                .andExpect(status().isOk());
    }

    // ---- #18: a credential change ends the sessions it opened ----

    @Test
    void anAdminPasswordResetRefusesTheAccountsExistingToken() throws Exception {
        Cookie victimSession = adminCookie(VICTIM, UserRole.HOD);

        mockMvc.perform(post("/api/admin/users/" + VICTIM + "/reset").with(csrf())
                        .cookie(adminCookie(ADMIN, UserRole.ADMIN)))
                .andExpect(status().isOk());

        mockMvc.perform(get(ANY_ADMIN_ENDPOINT).cookie(victimSession))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Changing your own password signs you out here too, and clears the cookie in the handler —
     * the same shape as change-username. Re-issuing a token instead would make this a third
     * endpoint that mints sessions; login should be the only one.
     */
    @Test
    void changingYourOwnPasswordSignsYouOutAndClearsTheCookie() throws Exception {
        Cookie session = adminCookie(VICTIM, UserRole.HOD);

        String setCookie = mockMvc.perform(post("/api/auth/change-password").with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"current-password\",\"newPassword\":\"BrandNew1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(setCookie).contains(SessionCookieService.ADMIN_COOKIE).contains("Max-Age=0");
        mockMvc.perform(get(ANY_ADMIN_ENDPOINT).cookie(session))
                .andExpect(status().isUnauthorized());
    }

    // ---- #18, student half: the date of birth IS the credential ----

    @Test
    void resettingAStudentsDateOfBirthRefusesTheirExistingToken() throws Exception {
        Cookie studentSession = studentCookie(STUDENT);

        mockMvc.perform(post("/api/admin/students/" + STUDENT + "/reset-dob").with(csrf())
                        .cookie(adminCookie(ADMIN, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateOfBirth\":\"2006-02-02\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ANY_STUDENT_ENDPOINT).cookie(studentSession))
                .andExpect(status().isUnauthorized());
    }

    /** Already true before V7 — every student endpoint resolves the row and 401s on a missing
     *  one — and asserted here so the filter's own branch cannot silently stop doing it. */
    @Test
    void aDeletedStudentsTokenIsRefused() throws Exception {
        Cookie studentSession = studentCookie(STUDENT);
        studentRepository.delete(studentRepository.findById(STUDENT).orElseThrow());
        studentRepository.flush();

        mockMvc.perform(get(ANY_STUDENT_ENDPOINT).cookie(studentSession))
                .andExpect(status().isUnauthorized());
    }
}
