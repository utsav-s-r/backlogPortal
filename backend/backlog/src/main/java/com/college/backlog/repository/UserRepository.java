package com.college.backlog.repository;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Department ids holding at least one account in {@code roles}. One query for the whole
     * departments page rather than an exists-check per row, the same shape as
     * {@code StudentRepository.findDistinctBranchCodes}. The caller supplies the role set — see
     * {@code AdminController.VERIFYING_DEPT_ROLES} for which one and why.
     */
    @Query("select distinct u.department.id from User u "
        + "where u.department is not null and u.role in :roles")
    Set<Long> findDepartmentIdsWithAnyOfRoles(@Param("roles") Collection<UserRole> roles);
}
