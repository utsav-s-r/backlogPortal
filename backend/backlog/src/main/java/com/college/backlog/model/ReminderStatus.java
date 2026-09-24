package com.college.backlog.model;

/**
 * SCHEDULED → SENDING (first run past send_at) → SENT (every resolved recipient is in the log).
 * CANCELLED from SCHEDULED or SENDING — the runner checks between sends and stops; emails already
 * sent stay sent. Never from SENT.
 * Mirrored by chk_reminders_status (V9).
 */
public enum ReminderStatus {
    SCHEDULED, SENDING, SENT, CANCELLED
}
