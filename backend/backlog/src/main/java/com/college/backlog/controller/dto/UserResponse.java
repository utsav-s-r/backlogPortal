package com.college.backlog.controller.dto;

import com.college.backlog.model.User;

/** Safe view of a {@link User} for admin user-management; never carries the password (stored as
 *  a one-way bcrypt hash). */
public class UserResponse {
    private String username;
    private String role;
    private Long departmentId;
    private String departmentName;

    public static UserResponse from(User user) {
        UserResponse r = new UserResponse();
        r.username = user.getUsername();
        r.role = user.getRole() != null ? user.getRole().name() : null;
        if (user.getDepartment() != null) {
            r.departmentId = user.getDepartment().getId();
            r.departmentName = user.getDepartment().getDeptName();
        }
        return r;
    }

    public String getUsername() { return username; }
    public String getRole() { return role; }
    public Long getDepartmentId() { return departmentId; }
    public String getDepartmentName() { return departmentName; }
}
