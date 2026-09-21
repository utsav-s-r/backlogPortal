package com.college.backlog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One line of the batch list printed above the student's details: {@code label} = "B.E. I to VII
 * Semester", {@code batch} = "2021". The "(", the " Batch Students)" and the bold/regular split
 * are the renderer's — they never vary, so they are not stored (same rule as the academic year's
 * "-26").
 *
 * <p>{@code batch} is text on purpose: a real row reads "2022 & 2023", which no numeric type or
 * year validation would accept.
 *
 * <p>Embeddable, not an entity: a line has no identity and no life outside its cycle. NOT NULL and
 * the lengths live in V5, not in {@code nullable=false} — the DB stays the stricter of the two.
 */
@Embeddable
public class BatchLine {

    @Column(name = "label")
    private String label;

    @Column(name = "batch")
    private String batch;

    public BatchLine() {}

    public BatchLine(String label, String batch) {
        this.label = label;
        this.batch = batch;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getBatch() { return batch; }
    public void setBatch(String batch) { this.batch = batch; }
}
