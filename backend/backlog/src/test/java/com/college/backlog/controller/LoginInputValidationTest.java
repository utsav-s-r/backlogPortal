package com.college.backlog.controller;

import com.college.backlog.exception.GlobalExceptionHandler;
import com.college.backlog.model.User;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.security.JwtService;
import com.college.backlog.security.SessionCookieService;
import com.college.backlog.service.AdminAuditService;
import com.college.backlog.service.CallerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both login endpoints bind a raw {@code Map<String, String>}, so a JSON null arrives as a present
 * key with a null value. It must get the same 400 as an absent field, never an NPE that
 * {@link GlobalExceptionHandler}'s catch-all answers as 500 and logs at ERROR. Standalone MockMvc
 * WITH the real advice attached: the 500 is produced there, so a test without it can't see the bug.
 */
@ExtendWith(MockitoExtension.class)
class LoginInputValidationTest {

    @Mock private UserRepository userRepository;
    @Mock private CallerScope callerScope;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private SessionCookieService sessionCookieService;
    @Mock private AdminAuditService auditService;
    @Mock private StudentRepository studentRepository;

    @InjectMocks private AuthController authController;
    @InjectMocks private StudentAuthController studentAuthController;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(authController, studentAuthController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResultActions login(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ---- staff login ----

    @Test
    void staffNullUsernameIs400() throws Exception {
        login("/api/auth/login", "{\"username\":null,\"password\":\"secret123\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username and password are required"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void staffNullPasswordIs400() throws Exception {
        login("/api/auth/login", "{\"username\":\"admin\",\"password\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username and password are required"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void staffAbsentFieldsStay400() throws Exception {
        login("/api/auth/login", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username and password are required"));
    }

    @Test
    void staffAccountWithNullRoleIs403AndGetsNoSession() throws Exception {
        // users.role is nullable. Refused only AFTER the password matched, and no token is issued.
        User user = new User();
        user.setUsername("norole");
        user.setPassword("$2a$hash");
        when(userRepository.findByUsername("norole")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "$2a$hash")).thenReturn(true);

        login("/api/auth/login", "{\"username\":\"norole\",\"password\":\"secret123\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This account has no role assigned. Contact admin."));
        verify(jwtService, never()).generateToken(anyString(), anyString());
        verify(sessionCookieService, never()).write(any(), anyString(), anyString());
    }

    @Test
    void staffNullRoleWithWrongPasswordIsStillThePlain401() throws Exception {
        // The role guard must not run before the password check, or it tells an unauthenticated
        // caller that this username exists and is broken.
        User user = new User();
        user.setUsername("norole");
        user.setPassword("$2a$hash");
        when(userRepository.findByUsername("norole")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        login("/api/auth/login", "{\"username\":\"norole\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    // ---- student login ----

    @Test
    void studentNullRollNoIs400() throws Exception {
        login("/api/student/auth/login", "{\"rollNo\":null,\"dateOfBirth\":\"2026-01-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("USN and date of birth are required"));
        verifyNoInteractions(studentRepository);
    }

    @Test
    void studentNullDateOfBirthIs400() throws Exception {
        login("/api/student/auth/login", "{\"rollNo\":\"1MS24CS001\",\"dateOfBirth\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("USN and date of birth are required"));
        verifyNoInteractions(studentRepository);
    }

    @Test
    void studentAbsentFieldsStay400() throws Exception {
        login("/api/student/auth/login", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("USN and date of birth are required"));
    }
}
