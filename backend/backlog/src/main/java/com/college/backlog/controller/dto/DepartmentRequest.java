package com.college.backlog.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class DepartmentRequest {

    @NotBlank(message = "Department name is required")
    private String deptName;

    // 2-letter branch code as it appears in the USN (e.g. "CS" in 1MS22CS001)
    @NotBlank(message = "Department code is required")
    @Pattern(regexp = "^[A-Za-z]{2}$", message = "Department code must be exactly 2 letters")
    private String code;

    @Email(message = "Contact email must be a valid address")
    private String contactEmail;

    // Optimistic-lock version the client last saw, sent on update so the server can reject a
    // stale overwrite (409). Null on create, where it is ignored.
    private Long version;

    public DepartmentRequest() {}

    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
