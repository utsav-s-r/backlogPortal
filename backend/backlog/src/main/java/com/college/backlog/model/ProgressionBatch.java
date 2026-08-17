package com.college.backlog.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Header of one bulk progression run: who ran it, when, over what selection, and the resulting
 * counts. Append-only — rows are never updated or deleted, like {@link RegistrationEvent}.
 *
 * Written in the SAME transaction as the bulk UPDATE, so a promotion can never commit without its
 * audit record. The per-student detail is {@link ProgressionBatchStudent}.
 */
@Entity
@Table(name = "progression_batches")
public class ProgressionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_username")
    private String actorUsername;

    @Column(name = "run_at")
    private Instant runAt;

    /** The selection the run was narrowed to; null = every semester / every department. */
    @Column(name = "filter_semester")
    private Integer filterSemester;

    @Column(name = "filter_dept_code")
    private String filterDeptCode;

    @Column(name = "promoted_count")
    private int promotedCount;

    @Column(name = "excluded_count")
    private int excludedCount;

    @Column(name = "skipped_count")
    private int skippedCount;

    public ProgressionBatch() {}

    public ProgressionBatch(String actorUsername, Integer filterSemester, String filterDeptCode) {
        this.actorUsername = actorUsername;
        this.filterSemester = filterSemester;
        this.filterDeptCode = filterDeptCode;
        this.runAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }

    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }

    public Integer getFilterSemester() { return filterSemester; }
    public void setFilterSemester(Integer filterSemester) { this.filterSemester = filterSemester; }

    public String getFilterDeptCode() { return filterDeptCode; }
    public void setFilterDeptCode(String filterDeptCode) { this.filterDeptCode = filterDeptCode; }

    public int getPromotedCount() { return promotedCount; }
    public void setPromotedCount(int promotedCount) { this.promotedCount = promotedCount; }

    public int getExcludedCount() { return excludedCount; }
    public void setExcludedCount(int excludedCount) { this.excludedCount = excludedCount; }

    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
}
