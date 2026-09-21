package com.college.backlog.model;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exam_cycles")
public class ExamCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // name UNIQUE and the single-active-cycle rule live at the DB level — the partial unique
    // index for single-active can't be expressed in JPA.
    @Column(nullable = false)
    private String name;

    @Column(name = "exam_month_year")
    private String examMonthYear;

    private boolean active;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * The batch list printed on this cycle's forms, in order. EAGER with @BatchSize, copying
     * Subject.eligibleDepartments: open-in-view is off and PdfService formats the cycle outside
     * any transaction, so LAZY would throw there. Eager + batch issues a separate batched SELECT
     * rather than a join — Registration.subjects is already a bag, and a second JOINED bag is
     * MultipleBagFetchException (docs/adr/persistence-fetching.md).
     *
     * <p>@OrderColumn, not @OrderBy: position is the stored order, and Hibernate rewrites the
     * whole list on every edit, which is the replace-the-block semantics the editor wants.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 50)
    @CollectionTable(name = "exam_cycle_batch_lines",
                     joinColumns = @JoinColumn(name = "exam_cycle_id"))
    @OrderColumn(name = "position")
    private List<BatchLine> batchLines = new ArrayList<>();

    public ExamCycle() {}

    public ExamCycle(String name, String examMonthYear) {
        this.name = name;
        this.examMonthYear = examMonthYear;
        this.active = false;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getExamMonthYear() { return examMonthYear; }
    public void setExamMonthYear(String examMonthYear) { this.examMonthYear = examMonthYear; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<BatchLine> getBatchLines() { return batchLines; }

    /** Copies, rather than holding the caller's list: Hibernate mutates this collection in place
     *  (the PersistentBag it manages, and clear()/addAll() on an edit), so a List.of(...) handed
     *  in here surfaces later as UnsupportedOperationException from deep inside a flush. Mapping
     *  is field-access, so Hibernate's own load never goes through this setter. */
    public void setBatchLines(List<BatchLine> batchLines) {
        this.batchLines = batchLines == null ? new ArrayList<>() : new ArrayList<>(batchLines);
    }
}
