package com.college.backlog.controller;

import com.college.backlog.model.User;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staff login runs exactly ONE bcrypt check on every failing path. Unknown user, a row with no
 * stored hash and a wrong password must cost the same, or response time tells a caller which
 * usernames exist despite the identical 401. Timing itself is too noisy to assert; the invariant
 * that produces it — one {@code matches} per attempt — is not.
 */
@ExtendWith(MockitoExtension.class)
class LoginTimingEqualizationTest {

    private static final String DUMMY = "$2a$10$dummy";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthController authController;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY);
        authController.initDummyHash();
    }

    private void assertPlain401(String username, String password) {
        assertThatThrownBy(() -> authController.login(
                Map.of("username", username, "password", password), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getReason()).isEqualTo("Invalid username or password");
                });
    }

    @Test
    void unknownUserStillPaysOneBcryptCheck() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertPlain401("ghost", "secret123");

        verify(passwordEncoder, times(1)).matches("secret123", DUMMY);
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void accountWithNoStoredHashStillPaysOneBcryptCheck() {
        User user = new User();
        user.setUsername("nohash");
        user.setPassword("  ");
        when(userRepository.findByUsername("nohash")).thenReturn(Optional.of(user));

        assertPlain401("nohash", "secret123");

        verify(passwordEncoder, times(1)).matches("secret123", DUMMY);
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void wrongPasswordPaysExactlyOneCheckAgainstTheRealHash() {
        User user = new User();
        user.setUsername("hod");
        user.setPassword("$2a$10$real");
        when(userRepository.findByUsername("hod")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$10$real")).thenReturn(false);

        assertPlain401("hod", "wrong");

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }
}
