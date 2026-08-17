package com.college.backlog.config;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // No fallback defaults: an unset env var leaves the password blank, and a blank one skips the
    // account rather than installing a guessable default (old issue #1). Set these in the env to
    // provision initial accounts on a fresh database.
    //
    // Only department-less roles (ADMIN, PRINCIPAL) are seedable: HOD/DEPT_OFFICE need a
    // department a fresh-database seeder can't assign, and login rejects a dept role without one,
    // so such an account could never sign in. Create those via Manage Users once an admin exists.
    @Value("${admin.password.principal:}")
    private String principalPassword;

    @Value("${admin.password.admin:}")
    private String adminPassword;

    @Override
    public void run(String... args) throws Exception {
        // created only when an explicit seed password is supplied; existing rows never overwritten,
        // and the seeded password stands until its holder changes it (/api/auth/change-password)
        seedUser("principal", principalPassword, UserRole.PRINCIPAL);
        seedUser("admin", adminPassword, UserRole.ADMIN);

        // one-time safety net: bcrypt legacy plaintext passwords, since the plaintext login
        // fallback has been removed
        migratePlaintextPasswords();
    }

    private void seedUser(String username, String password, UserRole role) {
        boolean hasPassword = password != null && !password.isBlank();
        User user = userRepository.findById(username).orElse(null);

        if (user == null) {
            if (!hasPassword) {
                // no env-supplied password — do NOT install a guessable default
                log.warn("Skipping seed of '{}' account: no admin.password.{} configured. "
                        + "Set it in the environment to provision this account.",
                        username, role.name().toLowerCase());
                return;
            }
            user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setRole(role);
            userRepository.save(user);
            return;
        }

        // row exists without a usable password: repair only from an explicit seed password
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            if (!hasPassword) {
                log.warn("Account '{}' has no usable password and no admin.password.{} is "
                        + "configured to repair it; leaving it untouched.",
                        username, role.name().toLowerCase());
                return;
            }
            user.setPassword(passwordEncoder.encode(password));
            if (user.getRole() == null) {
                user.setRole(role);
            }
            userRepository.save(user);
        }
    }

    private void migratePlaintextPasswords() {
        for (User user : userRepository.findAll()) {
            String pw = user.getPassword();
            if (pw != null && !pw.isBlank() && !isBcryptHash(pw)) {
                user.setPassword(passwordEncoder.encode(pw));
                userRepository.save(user);
            }
        }
    }

    private boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}

