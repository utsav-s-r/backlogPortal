package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateUserRequest {

    /** Minimum username length. Keeps the derived default password (username + "4321") at or above
     *  the 8-character floor {@link ChangePasswordRequest} imposes on chosen passwords. */
    public static final int USERNAME_MIN_LENGTH = 4;

    // Trimmed in the setter, NOT in the controller: Jackson binds before validation runs, so a
    // setter-side trim is what makes @Size see the same value that is persisted. Trimming after
    // validation instead would let "  ab  " pass as 6 characters and land as "ab", whose default
    // password ab4321 is 6 — under the floor this rule exists to guarantee.
    @NotBlank(message = "Username cannot be empty")
    @Size(min = USERNAME_MIN_LENGTH, message = "Username must be at least 4 characters")
    private String username;

    @NotBlank(message = "Role cannot be empty")
    private String role;

    // Required for HOD / DEPT_OFFICE / PROCTOR accounts; ignored for ADMIN / PRINCIPAL.
    private Long departmentId;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? null : username.trim(); }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
}
