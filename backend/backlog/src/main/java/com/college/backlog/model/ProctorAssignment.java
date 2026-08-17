package com.college.backlog.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One student's supervision by one proctor. The roll number is the primary key, which is what
 * enforces one-proctor-per-student — a second claim is a key conflict, not a silent reassignment.
 *
 * Both sides are plain string columns; the FKs (ON DELETE CASCADE both ways) live at the DB level
 * only, in V1__baseline.sql. Removing a row removes supervision and nothing else — it is not
 * history and never blocks a student delete.
 */
@Entity
@Table(name = "proctor_students")
public class ProctorAssignment {

    @Id
    @Column(name = "roll_no")
    private String rollNo;

    @Column(name = "proctor_username", nullable = false)
    private String proctorUsername;

    // who created it: the proctor themselves, or an HOD/admin
    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    public ProctorAssignment() {}

    public ProctorAssignment(String rollNo, String proctorUsername, String assignedBy) {
        this.rollNo = rollNo;
        this.proctorUsername = proctorUsername;
        this.assignedBy = assignedBy;
        this.assignedAt = LocalDateTime.now();
    }

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public String getProctorUsername() { return proctorUsername; }
    public void setProctorUsername(String proctorUsername) { this.proctorUsername = proctorUsername; }

    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }

    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }
}
