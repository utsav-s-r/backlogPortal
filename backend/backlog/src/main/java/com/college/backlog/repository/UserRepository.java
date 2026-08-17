package com.college.backlog.repository;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
    long countByRole(UserRole role);

    // Department-delete guard: blocks removing a department still assigned to a staff user
    // (dept_id FK on users).
    boolean existsByDepartment_Id(Long departmentId);
}