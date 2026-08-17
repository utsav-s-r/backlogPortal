package com.college.backlog.controller.dto;

import com.college.backlog.model.ProgressionBatch;

/** The committed run: its audit id and the counts, so the UI can link straight to the batch. */
public class BulkProgressionResultResponse {

    private Long batchId;
    private String actor;
    private String runAt;
    private Integer filterSemester;
    private String filterDeptCode;
    private int promotedCount;
    private int excludedCount;
    private int skippedCount;

    public BulkProgressionResultResponse(ProgressionBatch b) {
        this.batchId = b.getId();
        this.actor = b.getActorUsername();
        this.runAt = b.getRunAt() == null ? null : b.getRunAt().toString();
        this.filterSemester = b.getFilterSemester();
        this.filterDeptCode = b.getFilterDeptCode();
        this.promotedCount = b.getPromotedCount();
        this.excludedCount = b.getExcludedCount();
        this.skippedCount = b.getSkippedCount();
    }

    public Long getBatchId() { return batchId; }
    public String getActor() { return actor; }
    public String getRunAt() { return runAt; }
    public Integer getFilterSemester() { return filterSemester; }
    public String getFilterDeptCode() { return filterDeptCode; }
    public int getPromotedCount() { return promotedCount; }
    public int getExcludedCount() { return excludedCount; }
    public int getSkippedCount() { return skippedCount; }
}
