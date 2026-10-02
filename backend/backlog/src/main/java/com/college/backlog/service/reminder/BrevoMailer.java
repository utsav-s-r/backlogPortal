package com.college.backlog.service.reminder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Brevo's transactional email API over HTTPS — NOT SMTP: Render's free tier blocks outbound
 * 25/465/587, so spring-boot-starter-mail cannot reach any SMTP server from production.
 * The sender must be verified in the Brevo account; until a domain is authenticated Brevo shows it
 * as an @brevosend.com address (display name kept).
 */
@Component
public class BrevoMailer implements ReminderMailer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final String apiKey;
    private final String fromEmail;
    private final String fromName;

    @Autowired
    public BrevoMailer(RestClient.Builder builder,
                       @Value("${app.mail.brevo-url:https://api.brevo.com/v3/smtp/email}") String url,
                       @Value("${app.mail.brevo-api-key:}") String apiKey,
                       @Value("${app.mail.from:}") String fromEmail,
                       @Value("${app.mail.from-name:}") String fromName) {
        this(boundedClient(builder, url), apiKey, fromEmail, fromName);
    }

    // Bounded: an unbounded read would hang the single runner thread, and with it every later
    // reminder, on one stuck connection.
    private static RestClient boundedClient(RestClient.Builder builder, String url) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(10_000);
        timeouts.setReadTimeout(20_000);
        return builder.requestFactory(timeouts).baseUrl(url).build();
    }

    /** Tests: a client bound to MockRestServiceServer, which the builder above would override. */
    BrevoMailer(RestClient client, String apiKey, String fromEmail, String fromName) {
        this.client = client;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.fromEmail = fromEmail == null ? "" : fromEmail.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "MSRIT Backlog Portal" : fromName.trim();
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty() && !fromEmail.isEmpty();
    }

    @Override
    public String senderEmail() { return fromEmail; }

    @Override
    public String senderName() { return fromName; }

    @Override
    public void send(String toEmail, String toName, ReminderEmails.Content content) throws MailFailure {
        if (!isConfigured()) {
            throw new MailFailure(MailFailure.Kind.UNAVAILABLE, "Email sending is not configured.");
        }
        Map<String, Object> body = Map.of(
                "sender", Map.of("email", fromEmail, "name", fromName),
                // Map.of rejects nulls — a blank name is left out, never allowed to throw
                "to", List.of(toName == null || toName.isBlank()
                        ? Map.of("email", toEmail) : Map.of("email", toEmail, "name", toName)),
                "subject", content.subject(),
                "textContent", content.text(),
                "htmlContent", content.html());
        // Serialized here so the request carries Content-Length: handed an object, the client
        // streams it chunked, which not every endpoint accepts.
        byte[] json;
        try {
            json = JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the email", e);
        }
        try {
            client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("api-key", apiKey)
                    .contentLength(json.length)
                    .body(json)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String detail = new String(res.getBody().readAllBytes());
                        String msg = "Brevo " + res.getStatusCode().value() + ": " + abbreviate(detail);
                        throw new FailureCarrier(new MailFailure(
                                res.getStatusCode().value() == 400
                                        ? MailFailure.Kind.REJECTED : MailFailure.Kind.UNAVAILABLE,
                                msg));
                    })
                    .toBodilessEntity();
        } catch (FailureCarrier c) {
            throw c.failure;
        } catch (RestClientException e) {
            throw new MailFailure(MailFailure.Kind.UNAVAILABLE, "Email service unreachable: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** onStatus handlers may only throw unchecked exceptions; this carries the checked one out. */
    private static final class FailureCarrier extends RuntimeException {
        final MailFailure failure;
        FailureCarrier(MailFailure failure) {
            super(failure.getMessage(), null, false, false);
            this.failure = failure;
        }
    }
}
