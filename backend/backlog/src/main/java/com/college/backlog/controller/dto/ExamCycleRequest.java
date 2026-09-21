package com.college.backlog.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ExamCycleRequest {

    public static final int MAX_BATCH_LINES = 12;

    @NotBlank(message = "Cycle name is required")
    private String name;

    // Canonical "YYYY-MM". SHAPE, not context, so the annotation is the right place (see the
    // standing rule on Bean Validation). Free text here reached the exam cycles table and the
    // public /api/registration-status, and the printed form reads this field — three spellings
    // were already in use and "Not a valid month/year" was storable. Presentation ("June 2026")
    // is built in the UI's lib/examMonth.js; rows created before this stay as they are.
    @NotBlank(message = "Exam month / year is required")
    @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$",
             message = "Exam month / year must be YYYY-MM, e.g. 2026-06")
    private String examMonthYear;

    /**
     * The batch list printed on this cycle's forms, in the order given. Capped because the form is
     * ONE page: a longer list pushes the student's details off it, and nothing else would catch
     * that until someone printed one. Absent on create means "copy the previous cycle's list";
     * an empty list means "no batch block".
     */
    @Size(max = MAX_BATCH_LINES, message = "A cycle can list at most " + MAX_BATCH_LINES + " batches")
    private List<@Valid BatchLineRequest> batchLines;

    public ExamCycleRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getExamMonthYear() { return examMonthYear; }
    public void setExamMonthYear(String examMonthYear) { this.examMonthYear = examMonthYear; }

    public List<BatchLineRequest> getBatchLines() { return batchLines; }
    public void setBatchLines(List<BatchLineRequest> batchLines) { this.batchLines = batchLines; }

    /** One line: "B.E. I to VII Semester" + "2021". The punctuation around them is the PDF
     *  renderer's and is never sent. Lengths match V5's columns. */
    public static class BatchLineRequest {

        @NotBlank(message = "A batch line needs its programme and semesters")
        @Size(max = 80, message = "A batch line's text is at most 80 characters")
        private String label;

        // Text, not a year: a real row reads "2022 & 2023".
        @NotBlank(message = "A batch line needs its batch")
        @Size(max = 40, message = "A batch is at most 40 characters")
        private String batch;

        public BatchLineRequest() {}

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getBatch() { return batch; }
        public void setBatch(String batch) { this.batch = batch; }
    }
}
