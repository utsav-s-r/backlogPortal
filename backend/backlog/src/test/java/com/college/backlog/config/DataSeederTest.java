package com.college.backlog.config;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seeder needs its own tests because {@code @SpringBootTest} does NOT invoke
 * {@code CommandLineRunner} beans: the whole context suite can be green while this class does the
 * wrong thing on every real boot. Plain Mockito unit tests for that reason — no context, no database.
 *
 * <p>The case that matters most is {@link #doesNotResurrectTheAdminAfterItHasBeenRenamed}.
 */
@ExtendWith(MockitoExtension.class)
class DataSeederTest {

    @Mock private UserRepository userRepository;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private DataSeeder seeder(String adminPassword) {
        DataSeeder s = new DataSeeder();
        ReflectionTestUtils.setField(s, "userRepository", userRepository);
        ReflectionTestUtils.setField(s, "passwordEncoder", encoder);
        ReflectionTestUtils.setField(s, "adminPassword", adminPassword);
        return s;
    }

    private User existing(String username, UserRole role, String password) {
        User u = new User();
        u.setUsername(username);
        u.setRole(role);
        u.setPassword(password);
        return u;
    }

    @Test
    void seedsTheAdminOnAFreshDatabase() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(0L);
        when(userRepository.findAll()).thenReturn(List.of());

        seeder("s3cret-seed").run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        // stored bcrypted, never as the plaintext env value
        assertThat(saved.getValue().getPassword()).isNotEqualTo("s3cret-seed");
        assertThat(encoder.matches("s3cret-seed", saved.getValue().getPassword())).isTrue();
    }

    /**
     * THE regression this class exists for. Usernames are renamable since V4, so the seeded `admin`
     * may now legitimately be called something else. Keying the skip on the USERNAME would see no
     * `admin` row, and — with ADMIN_PASSWORD_ADMIN still set on the host — create a second
     * privileged account on the next cold boot. The skip keys on the ROLE instead.
     */
    @Test
    void doesNotResurrectTheAdminAfterItHasBeenRenamed() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L); // renamed, still an ADMIN
        when(userRepository.findAll()).thenReturn(
                List.of(existing("utsav", UserRole.ADMIN, encoder.encode("chosen"))));

        seeder("s3cret-seed").run();

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNotSeedWithoutAnExplicitPassword() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.findAll()).thenReturn(List.of());

        seeder("").run();

        // no guessable default is installed, and the role is never even consulted
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).countByRole(any());
    }

    @Test
    void neverOverwritesAnExistingAccountsPassword() throws Exception {
        String chosen = encoder.encode("the-holders-own-password");
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(existing("admin", UserRole.ADMIN, chosen)));
        when(userRepository.findAll()).thenReturn(
                List.of(existing("admin", UserRole.ADMIN, chosen)));

        seeder("s3cret-seed").run();

        // re-setting the env var must not reset a forgotten password (README states this)
        verify(userRepository, never()).save(any());
    }

    /** A row with no usable password IS repairable — the one case that may write to an existing
     *  account, and only from an explicit seed password. */
    @Test
    void repairsAnAccountLeftWithoutAPassword() throws Exception {
        User broken = existing("admin", UserRole.ADMIN, "");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(broken));
        when(userRepository.findAll()).thenReturn(List.of(broken));

        seeder("s3cret-seed").run();

        verify(userRepository).save(broken);
        assertThat(encoder.matches("s3cret-seed", broken.getPassword())).isTrue();
    }
}
