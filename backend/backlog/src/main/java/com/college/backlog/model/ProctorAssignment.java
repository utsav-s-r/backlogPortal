package com.college.backlog.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One student's supervision by one proctor. The roll number is the primary key, which is what
 * enforces one-proctor-per-student — a second claim is a key conflict, not a silent reassignment.
 *
 * The FKs (ON DELETE CASCADE both ways) live at the DB level only — roll_no in V1__baseline.sql,
 * proctor_user_id in V4. Removing a row removes supervision and nothing else — it is not history
 * and never blocks a student delete.
 */
@Entity
@Table(name = "proctor_students")
public class ProctorAssignment {

    @Id
    @Column(name = "roll_no")
    private String rollNo;

    // The proctor's surrogate id, not their username (V4): supervision must survive a rename, and
    // an id is what the FK can cascade on.
    @Column(name = "proctor_user_id", nullable = false)
    private Long proctorUserId;

    // Who created it: the proctor themselves, or an HOD/admin. Deliberately still a USERNAME and
    // deliberately un-FK'd — a display snapshot of who acted, which survives their account.
    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    public ProctorAssignment() {}

    public ProctorAssignment(String rollNo, Long proctorUserId, String assignedBy) {
        this.rollNo = rollNo;
        this.proctorUserId = proctorUserId;
        this.assignedBy = assignedBy;
        this.assignedAt = LocalDateTime.now();
    }

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public Long getProctorUserId() { return proctorUserId; }
    public void setProctorUserId(Long proctorUserId) { this.proctorUserId = proctorUserId; }

    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }

    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }
}
