package com.college.backlog.controller.dto;

import java.util.List;

/**
 * A bulk progression run: optional narrowing filters plus the admin's exclusion list.
 *
 * <p>{@code expectedCount} is required on COMMIT and ignored on preview. It carries the promoted
 * count the admin actually saw, and a mismatch is a 409 — which is also what stops a double-click:
 * after a successful run the candidate set has changed, so a stale confirmation cannot re-fire.
 */
public class BulkProgressionRequest {

    /** Narrow to one even semester; null = every semester. */
    private Integer semester;

    /** Narrow to one 2-letter branch code; null = every department. */
    private String deptCode;

    /** USNs to hold back (typically detained). An entry that is not a real student is a 400. */
    private List<String> excludeRollNos;

    /** Commit only: the promoted count from the preview the admin confirmed. */
    private Long expectedCount;

    public Integer getSemester() { return semester; }
    public void setSemester(Integer semester) { this.semester = semester; }

    public String getDeptCode() { return deptCode; }
    public void setDeptCode(String deptCode) { this.deptCode = deptCode; }

    public List<String> getExcludeRollNos() { return excludeRollNos; }
    public void setExcludeRollNos(List<String> excludeRollNos) { this.excludeRollNos = excludeRollNos; }

    public Long getExpectedCount() { return expectedCount; }
    public void setExpectedCount(Long expectedCount) { this.expectedCount = expectedCount; }
}
