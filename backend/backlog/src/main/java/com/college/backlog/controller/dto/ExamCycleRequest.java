package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotBlank;

public class ExamCycleRequest {

    @NotBlank(message = "Cycle name is required")
    private String name;

    private String examMonthYear;

    public ExamCycleRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getExamMonthYear() { return examMonthYear; }
    public void setExamMonthYear(String examMonthYear) { this.examMonthYear = examMonthYear; }
}
