package com.college.backlog.service.reminder;

import com.college.backlog.controller.dto.ReminderConfig;
import com.college.backlog.controller.dto.ReminderFailureItem;
import com.college.backlog.controller.dto.ReminderListItem;
import com.college.backlog.controller.dto.ReminderPreview;
import com.college.backlog.controller.dto.ReminderRequest;
import com.college.backlog.controller.dto.ReminderTestRequest;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.RecipientOutcome;
import com.college.backlog.model.Reminder;
import com.college.backlog.model.ReminderStatus;
import com.college.backlog.model.User;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ReminderRecipientRepository;
import com.college.backlog.repository.ReminderRepository;
import com.college.backlog.service.AdminAuditService;
import com.college.backlog.service.Emails;
import com.college.backlog.service.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Admin-side reminder operations. Authorization is the controller's (ADMIN only). */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    static final int MAX_SUBJECT = 150;
    static final int MAX_MESSAGE = 4000;
    /** Clock skew between the admin's form and the server; anything earlier is a typo. */
    private static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration MAX_AHEAD = Duration.ofDays(366);

    @Autowired private ReminderRepository reminderRepository;
    @Autowired private ReminderRecipientRepository recipientRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ReminderAudience audience;
    @Autowired private ReminderMailer mailer;
    @Autowired private ReminderRunner runner;
    @Autowired private AdminAuditService auditService;

    @Value("${app.reminders.cron-token:}")
    private String cronToken;

    @Value("${app.mail.daily-cap:280}")
    private int dailyCap;

    // Replaced in tests via ReflectionTestUtils.
    private Clock clock = Clock.systemUTC();

    public ReminderConfig config() {
        return new ReminderConfig(mailer.isConfigured(), mailer.senderEmail(), mailer.senderName(),
                cronToken != null && !cronToken.isBlank(), dailyCap);
    }

    @Transactional(readOnly = true)
    public List<ReminderListItem> list() {
        List<Reminder> reminders = reminderRepository.findAllByOrderBySendAtDesc();
        Map<Long, long[]> counts = new HashMap<>();
        // skipped when empty: an empty IN list is not valid SQL
        if (!reminders.isEmpty()) {
            for (Object[] row : recipientRepository.countByReminderAndOutcome(
                    reminders.stream().map(Reminder::getId).toList())) {
                long[] c = counts.computeIfAbsent((Long) row[0], k -> new long[2]);
                c[row[1] == RecipientOutcome.SENT ? 0 : 1] = (Long) row[2];
            }
        }
        return reminders.stream().map(r -> {
            long[] c = counts.getOrDefault(r.getId(), new long[2]);
            return ReminderListItem.of(r, c[0], c[1]);
        }).toList();
    }

    public ReminderPreview preview(ReminderRequest req) {
        ExamCycle cycle = requireCycle(req.getExamCycleId());
        String dept = resolveDeptCode(req.getDepartmentId());
        String subject = requireText(req.getSubject(), "Subject", MAX_SUBJECT);
        String message = requireText(req.getMessage(), "Message", MAX_MESSAGE);
        List<ReminderAudience.Recipient> recipients = audience.resolve(cycle.getId(), dept);
        ReminderAudience.Recipient sample = recipients.isEmpty() ? placeholder() : recipients.get(0);
        ReminderEmails.Content c = ReminderEmails.compose(subject, message, sample.name(),
                sample.rollNo(), cycle.getName(), sample.subjects());
        return new ReminderPreview(recipients.size(), sample.email(), c.subject(), c.text());
    }

    @Transactional
    public ReminderListItem create(ReminderRequest req, User actor) {
        ExamCycle cycle = requireCycle(req.getExamCycleId());
        String dept = resolveDeptCode(req.getDepartmentId());
        String subject = requireText(req.getSubject(), "Subject", MAX_SUBJECT);
        String message = requireText(req.getMessage(), "Message", MAX_MESSAGE);
        Instant sendAt = parseSendAt(req.getSendAt());
        Instant now = clock.instant();
        if (sendAt.isBefore(now.minus(PAST_TOLERANCE))) {
            throw bad("The send time is in the past.");
        }
        if (sendAt.isAfter(now.plus(MAX_AHEAD))) {
            throw bad("The send time must be within a year.");
        }
        Reminder saved = reminderRepository.save(
                new Reminder(cycle, dept, subject, message, sendAt, actor.getUsername(), now));
        auditService.record(AdminAuditAction.REMINDER_CREATE, actor, AuditTargetType.REMINDER,
                String.valueOf(saved.getId()),
                "cycle=" + cycle.getId() + " dept=" + (dept == null ? "ALL" : dept) + " sendAt=" + sendAt);
        invalidateAfterCommit();
        return ReminderListItem.of(saved, 0, 0);
    }

    @Transactional
    public ReminderListItem cancel(Long id, User actor) {
        Reminder r = reminderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reminder not found."));
        if (r.getStatus() != ReminderStatus.SCHEDULED && r.getStatus() != ReminderStatus.SENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This reminder is already " + r.getStatus().name().toLowerCase() + ".");
        }
        ReminderStatus was = r.getStatus();
        r.markCancelled(actor.getUsername(), clock.instant());
        try {
            reminderRepository.saveAndFlush(r);
        } catch (OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The reminder changed while you were cancelling it. Reload and try again.");
        }
        auditService.record(AdminAuditAction.REMINDER_CANCEL, actor, AuditTargetType.REMINDER,
                String.valueOf(id), "wasStatus=" + was);
        invalidateAfterCommit();
        return ReminderListItem.of(r, 0, 0);
    }

    @Transactional(readOnly = true)
    public List<ReminderFailureItem> failures(Long id) {
        if (!reminderRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reminder not found.");
        }
        return recipientRepository.findByReminderIdAndOutcomeOrderByRollNo(id, RecipientOutcome.FAILED)
                .stream()
                .map(f -> new ReminderFailureItem(f.getRollNo(), f.getEmail(), f.getError(), f.getProcessedAt()))
                .toList();
    }

    /** Not @Transactional: the email is sent either way, so the audit row must not be able to 500. */
    public String sendTest(ReminderTestRequest req, User actor) {
        String to = req.getTo() == null ? "" : req.getTo().trim();
        if (to.isEmpty() || to.length() > Emails.MAX_LENGTH || !to.matches(Emails.REGEX)) {
            throw bad("Enter a valid email address to send the test to.");
        }
        String subject = requireText(req.getSubject(), "Subject", MAX_SUBJECT);
        String message = requireText(req.getMessage(), "Message", MAX_MESSAGE);
        if (!mailer.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email sending is not configured on the server.");
        }
        ReminderAudience.Recipient p = placeholder();
        ReminderEmails.Content c = ReminderEmails.compose("[TEST] " + subject, message, p.name(),
                p.rollNo(), "Sample Exam Cycle", p.subjects());
        try {
            mailer.send(to, "Test recipient", c);
        } catch (MailFailure f) {
            log.warn("REMINDER_TEST_SEND_FAILED actor={} error={}", actor.getUsername(), f.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The email service refused it: " + f.getMessage());
        }
        return auditService.recordBestEffort(AdminAuditAction.REMINDER_TEST_SEND, actor,
                AuditTargetType.REMINDER, null, "to=" + to);
    }

    // ---- helpers ----

    /** The runner must re-read only once this transaction's reminder row is visible to it; an
     *  invalidate before commit lets a ping re-cache "nothing due" without the change. */
    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runner.invalidate();
                }
            });
        } else {
            runner.invalidate();
        }
    }

    private static ReminderAudience.Recipient placeholder() {
        return new ReminderAudience.Recipient("1MS00XX000", "Sample Student", "student@example.com",
                List.of("Example Subject (EX101)", "Another Subject (EX202)"));
    }

    /** Id from a BODY → 400, not 404 (status-code rules). */
    private ExamCycle requireCycle(Long id) {
        if (id == null) {
            throw bad("Pick an exam cycle.");
        }
        return examCycleRepository.findById(id).orElseThrow(() -> bad("Unknown exam cycle."));
    }

    private String resolveDeptCode(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        return departmentRepository.findById(departmentId)
                .map(d -> d.getCode())
                .orElseThrow(() -> bad("Unknown department."));
    }

    private static String requireText(String raw, String field, int max) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) {
            throw bad(field + " is required.");
        }
        if (v.length() > max) {
            throw bad(field + " must be at most " + max + " characters.");
        }
        return v;
    }

    private static Instant parseSendAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("Pick a send date and time.");
        }
        try {
            return LocalDateTime.parse(raw.trim()).atZone(Times.COLLEGE).toInstant();
        } catch (DateTimeParseException e) {
            throw bad("The send time must look like 2026-10-01T09:00.");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
