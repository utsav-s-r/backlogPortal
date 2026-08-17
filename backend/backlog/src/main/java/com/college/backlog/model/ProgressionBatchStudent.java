package com.college.backlog.model;

import jakarta.persistence.*;

/**
 * What one bulk progression run did to one student. Append-only detail of {@link ProgressionBatch}.
 *
 * <p>Almost every row is written by an {@code INSERT ... SELECT} in {@code StudentRepository}, not
 * through this entity — a whole-college run is ~20k rows, and materialising them in Java would turn
 * one statement into 20k round trips to Neon. The mapping exists so the audit can be READ back
 * (batch detail, per-student history) and so {@code ddl-auto=validate} covers the table; the small
 * exclusion set is the only part written entity-by-entity.
 *
 * <p>{@code rollNo} is a plain String, not a {@code @ManyToOne Student}: the audit row must outlive
 * the student it describes. Same reasoning as the registration snapshot columns.
 */
@Entity
@Table(name = "progression_batch_students")
public class ProgressionBatchStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "roll_no")
    private String rollNo;

    /** The semester the student was in when the run started — captured BEFORE the UPDATE fires. */
    @Column(name = "semester_from")
    private int semesterFrom;

    /** Only set for PROMOTED (always semesterFrom + 2); null otherwise, enforced by a DB CHECK. */
    @Column(name = "semester_to")
    private Integer semesterTo;

    @Enumerated(EnumType.STRING)
    private ProgressionOutcome outcome;

    public ProgressionBatchStudent() {}

    public ProgressionBatchStudent(Long batchId, String rollNo, int semesterFrom,
                                   Integer semesterTo, ProgressionOutcome outcome) {
        this.batchId = batchId;
        this.rollNo = rollNo;
        this.semesterFrom = semesterFrom;
        this.semesterTo = semesterTo;
        this.outcome = outcome;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public int getSemesterFrom() { return semesterFrom; }
    public void setSemesterFrom(int semesterFrom) { this.semesterFrom = semesterFrom; }

    public Integer getSemesterTo() { return semesterTo; }
    public void setSemesterTo(Integer semesterTo) { this.semesterTo = semesterTo; }

    public ProgressionOutcome getOutcome() { return outcome; }
    public void setOutcome(ProgressionOutcome outcome) { this.outcome = outcome; }
}
