package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service rename. The current password is required for the same reason
 * {@link ChangePasswordRequest} requires it: a live session alone must not be enough to change the
 * credential someone signs in with.
 */
public class ChangeUsernameRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    // Trimmed in the setter, NOT in the controller — Jackson binds before validation runs, so a
    // setter-side trim is what makes @Size see the value that is actually persisted. Same reasoning
    // and same floor as CreateUserRequest, whose constant this reuses: the minimum keeps the derived
    // default password (username + "4321") at or above ChangePasswordRequest's 8-character floor.
    @NotBlank(message = "New username cannot be empty")
    @Size(min = CreateUserRequest.USERNAME_MIN_LENGTH, message = "Username must be at least 4 characters")
    private String newUsername;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }

    public String getNewUsername() { return newUsername; }
    public void setNewUsername(String newUsername) {
        this.newUsername = newUsername == null ? null : newUsername.trim();
    }
}
