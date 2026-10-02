package com.college.backlog.controller;

import com.college.backlog.service.reminder.ReminderRunner;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The external cron's door (cron-job.org → POST, header X-Cron-Token). No session and no CSRF —
 * the cron has neither — so the shared secret is the whole control; SecurityConfig permits this one
 * path. Deliberately harmless even with a leaked token: no parameters, it only means "send what is
 * already due", and the send log makes a repeat a no-op.
 *
 * <p>Answers from memory when nothing is due (see ReminderRunner) — keep it that way: a DB read
 * here on every ping would keep Neon awake.
 */
@RestController
@RequestMapping("/api/internal/reminders")
public class ReminderTriggerController {

    static final int MIN_TOKEN_LENGTH = 32;

    @Autowired private ReminderRunner runner;

    @Value("${app.reminders.cron-token:}")
    private String cronToken;

    /** A short token is brute-forceable; refuse to boot rather than run with one. Unset is fine. */
    @PostConstruct
    void validateToken() {
        if (cronToken != null && !cronToken.isBlank() && cronToken.trim().length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("CRON_TOKEN must be at least " + MIN_TOKEN_LENGTH
                    + " characters (or unset to disable scheduled sending).");
        }
    }

    @PostMapping("/run")
    public ResponseEntity<Void> run(@RequestHeader(value = "X-Cron-Token", required = false) String given) {
        String expected = cronToken == null ? "" : cronToken.trim();
        if (expected.isEmpty()) {
            // unset = feature off; 404 says nothing about whether a token would work
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (given == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid cron token.");
        }
        return switch (runner.trigger()) {
            case STARTED, ALREADY_RUNNING -> ResponseEntity.accepted().build();
            case NOTHING_DUE -> ResponseEntity.noContent().build();
        };
    }
}
