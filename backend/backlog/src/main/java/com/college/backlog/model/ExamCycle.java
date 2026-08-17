package com.college.backlog.model;

import jakarta.persistence.*;
import java.time.Instant;

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
}
