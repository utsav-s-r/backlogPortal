package com.college.backlog.controller.dto;

import java.util.List;

/**
 * Bulk student import, mirroring the progression CSV-import flow. Rows omitting currentSemester /
 * entrySemester fall back to the batch defaults below (entrySemester defaults to 1 = normal
 * intake). dryRun validates without writing.
 */
public class StudentImportRequest {
    private List<StudentImportRow> rows;
    private Integer defaultCurrentSemester;
    private Integer defaultEntrySemester;
    private boolean dryRun;

    public List<StudentImportRow> getRows() { return rows; }
    public void setRows(List<StudentImportRow> rows) { this.rows = rows; }

    public Integer getDefaultCurrentSemester() { return defaultCurrentSemester; }
    public void setDefaultCurrentSemester(Integer defaultCurrentSemester) { this.defaultCurrentSemester = defaultCurrentSemester; }

    public Integer getDefaultEntrySemester() { return defaultEntrySemester; }
    public void setDefaultEntrySemester(Integer defaultEntrySemester) { this.defaultEntrySemester = defaultEntrySemester; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
}
