package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Edit an existing student. USN and dateOfBirth are NOT editable here: the USN is the identity key
 * and DOB (the login credential) changes only via reset-DOB. {@code email}: null (omitted) keeps
 * the stored address, blank resets it to {@code <usn>@msrit.edu}, anything else must be valid
 * ({@code Emails}). currentSemester is correctable — it shifts the eligibility window,
 * so the UI warns. Validated 1 ≤ entry ≤ current ≤ 8.
 */
public class StudentUpdateRequest {

    @NotBlank
    private String name;

    private String phone;

    private String email;

    private int currentSemester;

    private int entrySemester = 1;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public int getCurrentSemester() { return currentSemester; }
    public void setCurrentSemester(int currentSemester) { this.currentSemester = currentSemester; }

    public int getEntrySemester() { return entrySemester; }
    public void setEntrySemester(int entrySemester) { this.entrySemester = entrySemester; }
}
