package com.college.backlog.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/** One row of a student CSV import. currentSemester / entrySemester are nullable — blank falls
 *  back to the batch defaults on {@link StudentImportRequest}. */
public class StudentImportRow {
    private String rollNo;
    private String name;
    private String phone;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    private Integer currentSemester;
    private Integer entrySemester;

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public Integer getCurrentSemester() { return currentSemester; }
    public void setCurrentSemester(Integer currentSemester) { this.currentSemester = currentSemester; }

    public Integer getEntrySemester() { return entrySemester; }
    public void setEntrySemester(Integer entrySemester) { this.entrySemester = entrySemester; }
}
