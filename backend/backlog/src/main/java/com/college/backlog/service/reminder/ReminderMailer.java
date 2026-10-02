package com.college.backlog.service.reminder;

/** Outbound email. One implementation ({@link BrevoMailer}); an interface so tests need no network. */
public interface ReminderMailer {

    /** Both the API key and the sender address are set. Nothing is sent otherwise. */
    boolean isConfigured();

    String senderEmail();

    String senderName();

    void send(String toEmail, String toName, ReminderEmails.Content content) throws MailFailure;
}
