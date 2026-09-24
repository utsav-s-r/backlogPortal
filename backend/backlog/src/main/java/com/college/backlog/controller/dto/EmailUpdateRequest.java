package com.college.backlog.controller.dto;

/** Student's own email change. Required; blank resets to the institutional address. Checked in
 *  {@code StudentManagementService.changeOwnEmail}, not by annotation, because blank is legal. */
public class EmailUpdateRequest {

    private String email;

    public EmailUpdateRequest() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
