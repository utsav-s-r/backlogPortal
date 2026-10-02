package com.college.backlog.service.reminder;

import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * Builds one student's email: the admin's subject and message, framed by a fixed greeting, the
 * student's verified subjects and a no-reply footer the admin cannot edit. Plain text is the
 * source; the HTML part is the same text escaped (Brevo requires an HTML part).
 */
public final class ReminderEmails {

    public record Content(String subject, String text, String html) {}

    static final String FOOTER = "This is an automated reminder from the MSRIT Backlog Portal. "
            + "Replies to this address are not read — for any query, contact your department office in person.";

    private ReminderEmails() {}

    public static Content compose(String subject, String message, String studentName, String rollNo,
                                  String cycleName, List<String> subjects) {
        StringBuilder text = new StringBuilder()
                .append("Dear ").append(studentName == null || studentName.isBlank() ? rollNo : studentName).append(" (").append(rollNo).append("),\n\n")
                .append(message.strip()).append("\n\n")
                .append("Your verified backlog subjects for ").append(cycleName).append(":\n");
        if (subjects.isEmpty()) {
            text.append("  (none listed)\n");
        }
        for (String s : subjects) {
            text.append("  - ").append(s).append('\n');
        }
        text.append("\n--\n").append(FOOTER).append('\n');
        String body = text.toString();
        String html = "<div style=\"font-family:Arial,sans-serif;font-size:14px;line-height:1.5;"
                + "white-space:pre-wrap\">" + HtmlUtils.htmlEscape(body) + "</div>";
        // one line: a subject is a header, and a line break there has no meaning anywhere
        return new Content(subject.replaceAll("[\\r\\n]+", " ").strip(), body, html);
    }
}
