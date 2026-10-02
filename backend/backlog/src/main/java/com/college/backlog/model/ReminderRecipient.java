package com.college.backlog.model;

import jakarta.persistence.*;

import java.time.Instant;

/** One row per student a reminder was attempted for — the send log (see V9). Append-only. */
@Entity
@Table(name = "reminder_recipients")
public class ReminderRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reminder_id")
    private Long reminderId;

    @Column(name = "roll_no")
    private String rollNo;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RecipientOutcome outcome;

    @Column(length = 500)
    private String error;

    @Column(name = "processed_at")
    private Instant processedAt;

    public ReminderRecipient() {}

    public ReminderRecipient(Long reminderId, String rollNo, String email, RecipientOutcome outcome,
                             String error, Instant processedAt) {
        this.reminderId = reminderId;
        this.rollNo = rollNo;
        this.email = email;
        this.outcome = outcome;
        this.error = error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error);
        this.processedAt = processedAt;
    }

    public Long getId() { return id; }
    public Long getReminderId() { return reminderId; }
    public String getRollNo() { return rollNo; }
    public String getEmail() { return email; }
    public RecipientOutcome getOutcome() { return outcome; }
    public String getError() { return error; }
    public Instant getProcessedAt() { return processedAt; }
}
