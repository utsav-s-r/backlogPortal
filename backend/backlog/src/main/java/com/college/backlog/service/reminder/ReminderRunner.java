package com.college.backlog.service.reminder;

import com.college.backlog.model.RecipientOutcome;
import com.college.backlog.model.Reminder;
import com.college.backlog.model.ReminderRecipient;
import com.college.backlog.model.ReminderStatus;
import com.college.backlog.repository.ReminderRecipientRepository;
import com.college.backlog.repository.ReminderRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends due reminders. Triggered by the external cron (ReminderTriggerController), never by a
 * timer in this process: Render's free instance sleeps, so an in-process schedule would not fire.
 *
 * <p><b>No database when nothing is due.</b> {@link #quietUntil} holds "no reminder is due before
 * this instant", so the hourly ping answers from memory and Neon stays suspended — a ping that
 * queried every time would wake the compute each hour and burn the free CU-hours. Null = unknown
 * (fresh boot, or invalidated by a create/cancel), which costs exactly one query.
 *
 * <p><b>At-least-once per student.</b> A recipient row is written after the provider accepts the
 * email; the unique (reminder_id, roll_no) key and the handled-set skip make every re-run resume.
 * A crash between accept and write can send one student a duplicate — never skip one.
 *
 * <p>Failures: a 400 once any send in the run has succeeded is that recipient's problem → FAILED
 * row, not retried. 400s before any success are held until a success proves the setup (see
 * process); anything else (key, rate limit, outage) says nothing about the recipient → the run
 * stops, {@code last_error} shows it, the next ping resumes. Cancel is honoured between sends.
 */
@Service
public class ReminderRunner {

    public enum TriggerResult { STARTED, ALREADY_RUNNING, NOTHING_DUE }

    private static final Logger log = LoggerFactory.getLogger(ReminderRunner.class);
    private static final Set<ReminderStatus> UNFINISHED = EnumSet.of(ReminderStatus.SCHEDULED, ReminderStatus.SENDING);
    private static final int MAX_UNPROVEN = 3;
    private static final Instant NEVER = Instant.parse("9999-12-31T00:00:00Z");

    @Autowired private ReminderRepository reminderRepository;
    @Autowired private ReminderRecipientRepository recipientRepository;
    @Autowired private ReminderAudience audience;
    @Autowired private ReminderMailer mailer;

    /** Brevo's free plan allows 300/day; the default leaves room for test sends. */
    @Value("${app.mail.daily-cap:280}")
    private int dailyCap;

    // Replaced in tests via ReflectionTestUtils.
    private Clock clock = Clock.systemUTC();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant quietUntil;
    // Bumped by invalidate(). A run writes quietUntil only if no invalidate happened since it
    // started — else a reminder committed mid-run (after the run's final query) would be hidden
    // behind a "nothing due" computed without it, and never send.
    private final Object quietLock = new Object();
    private long epoch;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reminder-runner");
        t.setDaemon(true);
        return t;
    });

    /** Answers the cron immediately; the sending itself outlives its ~30s request timeout. */
    public TriggerResult trigger() {
        Instant quiet = quietUntil;
        if (quiet != null && clock.instant().isBefore(quiet)) {
            return TriggerResult.NOTHING_DUE;
        }
        if (!running.compareAndSet(false, true)) {
            return TriggerResult.ALREADY_RUNNING;
        }
        try {
            executor.submit(() -> {
                try {
                    runDue();
                } catch (RuntimeException e) {
                    log.error("REMINDER_RUN_FAILED", e);
                    quietUntil = null;
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return TriggerResult.STARTED;
    }

    /** A create or cancel changed what is due; the next ping re-reads. Call AFTER commit
     *  (ReminderService does) — before it, a ping could re-read without the change. */
    public void invalidate() {
        synchronized (quietLock) {
            epoch++;
            quietUntil = null;
        }
    }

    /** Synchronous body of a run. Package-visible for tests. */
    void runDue() {
        if (!mailer.isConfigured()) {
            // No DB touched; stays "unknown" so the first ping after configuration runs.
            log.warn("REMINDER_RUN_SKIPPED mail not configured");
            return;
        }
        long startedAt;
        synchronized (quietLock) {
            startedAt = epoch;
        }
        Instant now = clock.instant();
        List<Reminder> due = reminderRepository
                .findByStatusInAndSendAtLessThanEqualOrderBySendAtAsc(UNFINISHED, now);
        for (Reminder r : due) {
            if (!process(r)) {
                break;
            }
        }
        Instant earliest = reminderRepository.findEarliestSendAt(UNFINISHED).orElse(NEVER);
        synchronized (quietLock) {
            if (epoch == startedAt) {
                quietUntil = earliest;
            }
        }
    }

    /** @return false when the run must stop (provider trouble or the daily cap). */
    private boolean process(Reminder reminder) {
        Instant now = clock.instant();
        reminder.markSending(now);
        try {
            reminder = reminderRepository.save(reminder);
        } catch (OptimisticLockingFailureException e) {
            log.info("REMINDER_CANCELLED_BEFORE_START reminder={}", reminder.getId());
            return true;
        }

        List<ReminderAudience.Recipient> recipients =
                audience.resolve(reminder.getExamCycle().getId(), reminder.getDepartmentCode());
        Set<String> handled = recipientRepository.findHandledRollNos(reminder.getId());
        long budget = dailyCap - recipientRepository.countByOutcomeAndProcessedAtAfter(
                RecipientOutcome.SENT, now.minus(Duration.ofHours(24)));
        boolean anySuccess = false;
        // 400s seen before any success: could be this address or the whole setup. A later success
        // proves the setup, so these become FAILED; MAX_UNPROVEN in a row stops the run instead.
        // Recording them FAILED straight away would fail a whole reminder over a bad API key;
        // stopping on the first would re-fail the same student every run, forever.
        List<ReminderAudience.Recipient> unproven = new ArrayList<>();
        List<String> unprovenErrors = new ArrayList<>();

        for (ReminderAudience.Recipient r : recipients) {
            if (handled.contains(r.rollNo())) {
                continue;
            }
            if (cancelledMeanwhile(reminder.getId())) {
                log.info("REMINDER_CANCELLED_MID_RUN reminder={}", reminder.getId());
                return true;
            }
            if (budget <= 0) {
                return stop(reminder, "Daily email limit (" + dailyCap + " per 24h) reached; "
                        + "the rest send on a later run.");
            }
            ReminderEmails.Content content = ReminderEmails.compose(reminder.getSubject(),
                    reminder.getMessage(), r.name(), r.rollNo(), reminder.getExamCycle().getName(),
                    r.subjects());
            try {
                mailer.send(r.email(), r.name(), content);
            } catch (MailFailure f) {
                if (f.kind() != MailFailure.Kind.REJECTED) {
                    return stop(reminder, f.getMessage());
                }
                if (anySuccess) {
                    recordFailed(reminder, r, f.getMessage());
                    continue;
                }
                unproven.add(r);
                unprovenErrors.add(f.getMessage());
                if (unproven.size() >= MAX_UNPROVEN) {
                    return stop(reminder, f.getMessage());
                }
                continue;
            } catch (RuntimeException e) {
                // A bug, not the provider — but escaping would leave the reminder SENDING with no
                // last_error, failing silently on every run. Stop where the page can show it.
                log.error("REMINDER_SEND_UNEXPECTED reminder={} rollNo={}", reminder.getId(), r.rollNo(), e);
                return stop(reminder, "Unexpected error sending to " + r.rollNo() + ": " + e);
            }
            recipientRepository.save(new ReminderRecipient(reminder.getId(), r.rollNo(), r.email(),
                    RecipientOutcome.SENT, null, clock.instant()));
            budget--;
            if (!anySuccess) {
                anySuccess = true;
                for (int i = 0; i < unproven.size(); i++) {
                    recordFailed(reminder, unproven.get(i), unprovenErrors.get(i));
                }
                unproven.clear();
            }
        }
        if (!unproven.isEmpty()) {
            // Every attempt this run was refused and nothing proved the setup — can't blame the
            // addresses. Left unrecorded so the next run tries them again.
            return stop(reminder, unprovenErrors.get(unprovenErrors.size() - 1));
        }
        reminder.markSent(clock.instant());
        try {
            reminderRepository.save(reminder);
        } catch (OptimisticLockingFailureException e) {
            // cancelled after the last check — the cancel stands
            log.info("REMINDER_CANCELLED_MID_RUN reminder={}", reminder.getId());
            return true;
        }
        log.info("REMINDER_SENT reminder={} recipients={}", reminder.getId(), recipients.size());
        return true;
    }

    private boolean cancelledMeanwhile(Long reminderId) {
        return reminderRepository.findById(reminderId)
                .map(r -> r.getStatus() == ReminderStatus.CANCELLED)
                .orElse(true);
    }

    private void recordFailed(Reminder reminder, ReminderAudience.Recipient r, String error) {
        recipientRepository.save(new ReminderRecipient(reminder.getId(), r.rollNo(), r.email(),
                RecipientOutcome.FAILED, error, clock.instant()));
        log.warn("REMINDER_RECIPIENT_FAILED reminder={} rollNo={} error={}", reminder.getId(), r.rollNo(), error);
    }

    private boolean stop(Reminder reminder, String error) {
        log.error("REMINDER_RUN_STOPPED reminder={} error={}", reminder.getId(), error);
        reminder.setLastError(error);
        try {
            reminderRepository.save(reminder);
        } catch (OptimisticLockingFailureException e) {
            log.info("REMINDER_CANCELLED_MID_RUN reminder={}", reminder.getId());
        }
        return false;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
