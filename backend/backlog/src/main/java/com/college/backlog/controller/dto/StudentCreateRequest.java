package com.college.backlog.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Create one student account. The USN (rollNo) is the primary key and the source of branch +
 * admission year; dateOfBirth is the login credential; currentSemester and entrySemester drive the
 * eligibility window (validated 1 ≤ entry ≤ current ≤ 8 server-side). Email is system-managed
 * ({@code <usn>@msrit.edu}), never client-supplied; phone is optional.
 */
public class StudentCreateRequest {

    @NotBlank
    private String rollNo;

    @NotBlank
    private String name;

    private String phone;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    private int currentSemester;

    // defaults to 1 (normal intake) when the client omits it
    private int entrySemester = 1;

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public int getCurrentSemester() { return currentSemester; }
    public void setCurrentSemester(int currentSemester) { this.currentSemester = currentSemester; }

    public int getEntrySemester() { return entrySemester; }
    public void setEntrySemester(int entrySemester) { this.entrySemester = entrySemester; }
}
