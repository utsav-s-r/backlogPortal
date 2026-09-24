package com.college.backlog.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A scheduled email to the students with VERIFIED registrations in {@link #examCycle}, optionally
 * only those of one department. Stores the audience RULE, not a recipient list — resolved at send
 * time (see V9). Written by ReminderService (create/cancel) and ReminderRunner (sending); never
 * edited: cancel and re-create instead.
 */
@Entity
@Table(name = "reminders")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "exam_cycle_id")
    private ExamCycle examCycle;

    /** Student branch code (USN segment); null = every department. */
    @Column(name = "department_code", length = 16)
    private String departmentCode;

    @Column(length = 150)
    private String subject;

    @Column(length = 4000)
    private String message;

    @Column(name = "send_at")
    private Instant sendAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ReminderStatus status;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Optimistic lock: cancel (SCHEDULED only) and the runner's SCHEDULED -> SENDING can race. */
    @Version
    private long version;

    public Reminder() {}

    public Reminder(ExamCycle examCycle, String departmentCode, String subject, String message,
                    Instant sendAt, String createdBy, Instant createdAt) {
        this.examCycle = examCycle;
        this.departmentCode = departmentCode;
        this.subject = subject;
        this.message = message;
        this.sendAt = sendAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.status = ReminderStatus.SCHEDULED;
    }

    public Long getId() { return id; }
    public ExamCycle getExamCycle() { return examCycle; }
    public String getDepartmentCode() { return departmentCode; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
    public Instant getSendAt() { return sendAt; }
    public ReminderStatus getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getLastError() { return lastError; }

    public void markSending(Instant now) {
        if (status == ReminderStatus.SCHEDULED) {
            status = ReminderStatus.SENDING;
            startedAt = now;
        }
    }

    public void markSent(Instant now) {
        status = ReminderStatus.SENT;
        completedAt = now;
        lastError = null;
    }

    public void markCancelled(String actor, Instant now) {
        status = ReminderStatus.CANCELLED;
        cancelledBy = actor;
        cancelledAt = now;
    }

    /** Truncated to the column: an error message must never be the thing that fails the write. */
    public void setLastError(String error) {
        this.lastError = error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error);
    }
}
