package com.college.backlog.service.reminder;

/**
 * A send that did not go through. {@link Kind#REJECTED} = the provider refused this message
 * (HTTP 400 — possibly just this recipient); {@link Kind#UNAVAILABLE} = anything that says nothing
 * about the recipient (bad key, rate limit, outage, network), so the whole run should stop.
 */
public class MailFailure extends Exception {

    public enum Kind { REJECTED, UNAVAILABLE }

    private final Kind kind;

    public MailFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MailFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
