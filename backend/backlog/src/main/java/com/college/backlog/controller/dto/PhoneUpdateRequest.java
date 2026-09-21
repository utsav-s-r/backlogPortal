package com.college.backlog.controller.dto;

import com.college.backlog.service.Phones;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class PhoneUpdateRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = Phones.REGEX, message = Phones.MESSAGE)
    private String phone;

    public PhoneUpdateRequest() {}

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
