package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class PhoneUpdateRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;

    public PhoneUpdateRequest() {}

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
