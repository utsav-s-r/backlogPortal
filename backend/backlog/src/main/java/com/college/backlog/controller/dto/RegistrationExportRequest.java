package com.college.backlog.controller.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of POST /api/admin/export-pdf.
 *
 * POST rather than GET because a hand-picked {@code regIds} list overflows a URL at a few hundred
 * rows, and because generating a report is not a cacheable resource fetch.
 *
 * Two ways to name the rows, and {@code regIds} wins: a non-empty list IS the export set and every
 * filter below is ignored. The caller's dept pin and proctor scope are NOT ignored — they are
 * re-applied server-side, since an id list from a browser is otherwise an IDOR.
 */
public class RegistrationExportRequest {

    public static final int MAX_SELECTION = 1000;

    /** Explicit row selection. Non-empty ⇒ definitive; the filters are not applied. */
    // no trailing period — the validation handler appends one
    @Size(max = MAX_SELECTION, message = "Too many rows selected at once; narrow the filters instead")
    private List<String> regIds;

    private Long subjectId;
    private String subjectType;
    private String searchQuery;
    private Integer semester;
    private Long departmentId;
    private Long examCycleId;

    /** Blank/absent = VERIFIED. The report has always meant "the verified list" by default. */
    private String status;

    /**
     * Explicit opt-in to span every exam cycle. Without it an absent {@code examCycleId} falls back
     * to the active cycle — and with no active cycle the request is rejected rather than quietly
     * producing an empty PDF.
     */
    private boolean allCycles;

    public List<String> getRegIds() { return regIds; }
    public void setRegIds(List<String> regIds) { this.regIds = regIds; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public String getSearchQuery() { return searchQuery; }
    public void setSearchQuery(String searchQuery) { this.searchQuery = searchQuery; }

    public Integer getSemester() { return semester; }
    public void setSemester(Integer semester) { this.semester = semester; }

    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }

    public Long getExamCycleId() { return examCycleId; }
    public void setExamCycleId(Long examCycleId) { this.examCycleId = examCycleId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isAllCycles() { return allCycles; }
    public void setAllCycles(boolean allCycles) { this.allCycles = allCycles; }

    public boolean hasSelection() { return regIds != null && !regIds.isEmpty(); }
}
