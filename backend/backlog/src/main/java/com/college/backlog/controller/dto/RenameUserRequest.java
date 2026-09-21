package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ADMIN renaming somebody else's account. No password field — the caller proves nothing about the
 * target, only about their own authority, which {@code @PreAuthorize} and {@code CallerScope}
 * already settle. Self-rename goes through {@link ChangeUsernameRequest} instead, which does
 * require the password.
 */
public class RenameUserRequest {

    // Trimmed in the setter for the same reason as CreateUserRequest: Jackson binds before
    // validation, so trimming later would let "  ab  " pass @Size as 6 and land as 2.
    @NotBlank(message = "New username cannot be empty")
    @Size(min = CreateUserRequest.USERNAME_MIN_LENGTH, message = "Username must be at least 4 characters")
    private String newUsername;

    public String getNewUsername() { return newUsername; }
    public void setNewUsername(String newUsername) {
        this.newUsername = newUsername == null ? null : newUsername.trim();
    }
}
