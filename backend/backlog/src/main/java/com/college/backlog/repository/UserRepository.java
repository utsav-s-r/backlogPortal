package com.college.backlog.repository;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Keyed by the surrogate id since V4, but almost every caller arrives holding a USERNAME — it is
 * the JWT subject and the path variable — so {@link #findByUsername} is the workhorse, not
 * {@code findById}. Same single indexed lookup (uq_users_username).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRole(UserRole role);

    // Department-delete guard: blocks removing a department still assigned to a staff user
    // (dept_id FK on users).
    boolean existsByDepartment_Id(Long departmentId);
}
