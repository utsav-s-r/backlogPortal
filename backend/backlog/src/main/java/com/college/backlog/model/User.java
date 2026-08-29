package com.college.backlog.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    // Surrogate key (V4). Identity is the id; username is a mutable attribute, which is what lets
    // an account be renamed without touching proctor_students' FK. The SECURITY principal is still
    // the username (JwtService subject, auth.getName()) — only the database key is the id, so a
    // rename revokes the live session via AccountExistenceFilter rather than silently continuing.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Login credential and JWT subject, so still globally unique — uq_users_username in V4 is the
    // control; `unique` here is documentation, as validate never checks unique constraints.
    @Column(nullable = false, unique = true)
    private String username;

    private String password;

    // Stored as the enum name (varchar), used verbatim as the Spring Security authority and JWT
    // role claim, with a DB CHECK constraint on the values.
    @Enumerated(EnumType.STRING)
    private UserRole role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "dept_id", nullable = true)
    private Department department;

    public User() {}

    public User(String username, String password, UserRole role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    /** Assigned by the database; no setter — nothing may reassign an account's identity. */
    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
}
