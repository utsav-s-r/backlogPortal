package com.college.backlog.service.reminder;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Status mapping is the part that matters: it decides "fail this student" vs "stop the run". */
class BrevoMailerTest {

    private static final String URL = "https://api.brevo.test/v3/smtp/email";
    private static final ReminderEmails.Content CONTENT =
            ReminderEmails.compose("Subj", "Msg", "Asha", "1MS24CS001", "Cycle", List.of());

    private MockRestServiceServer server;
    private BrevoMailer mailer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        server = MockRestServiceServer.bindTo(builder).build();
        mailer = new BrevoMailer(builder.build(), "xkeysib-test", "portal@gmail.com", "Portal");
    }

    @Test
    void sendsTheKeySenderRecipientAndBothParts() throws Exception {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-test"))
                .andExpect(jsonPath("$.sender.email").value("portal@gmail.com"))
                .andExpect(jsonPath("$.sender.name").value("Portal"))
                .andExpect(jsonPath("$.to[0].email").value("a@gmail.com"))
                .andExpect(jsonPath("$.subject").value("Subj"))
                .andExpect(jsonPath("$.textContent").value(CONTENT.text()))
                .andExpect(jsonPath("$.htmlContent").value(CONTENT.html()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<x@brevo>\"}"));

        mailer.send("a@gmail.com", "Asha", CONTENT);
        server.verify();
    }

    /** Map.of throws on null — a nameless student must not crash the send. */
    @Test
    void aBlankRecipientNameIsLeftOut() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.to[0].email").value("a@gmail.com"))
                .andExpect(jsonPath("$.to[0].name").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED));

        mailer.send("a@gmail.com", null, CONTENT);
        server.verify();
    }

    @Test
    void a400IsRejectedWithBrevosReason() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"invalid_parameter\",\"message\":\"email is not valid in to\"}"));

        assertThatThrownBy(() -> mailer.send("a@gmail.com", "Asha", CONTENT))
                .isInstanceOf(MailFailure.class)
                .satisfies(e -> assertThat(((MailFailure) e).kind()).isEqualTo(MailFailure.Kind.REJECTED))
                .hasMessageContaining("Brevo 400").hasMessageContaining("email is not valid");
    }

    @Test
    void aBadKeyRateLimitOrOutageIsUnavailable() {
        for (HttpStatus s : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(s).body("{\"message\":\"nope\"}"));

            assertThatThrownBy(() -> mailer.send("a@gmail.com", "Asha", CONTENT))
                    .isInstanceOf(MailFailure.class)
                    .satisfies(e -> assertThat(((MailFailure) e).kind()).isEqualTo(MailFailure.Kind.UNAVAILABLE));
        }
    }

    /**
     * Over a REAL socket through the production constructor: MockRestServiceServer buffers every
     * request, so it reports a Content-Length even when the wire is chunked. The live check caught
     * exactly that — a chunked body was reset by a strict endpoint.
     */
    @Test
    void theWireRequestHasAFixedContentLengthNotChunked() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> length = new AtomicReference<>();
        AtomicReference<String> encoding = new AtomicReference<>();
        http.createContext("/v3/smtp/email", ex -> {
            length.set(ex.getRequestHeaders().getFirst("Content-Length"));
            encoding.set(ex.getRequestHeaders().getFirst("Transfer-Encoding"));
            ex.getRequestBody().readAllBytes();
            byte[] ok = "{\"messageId\":\"x\"}".getBytes();
            ex.sendResponseHeaders(201, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        http.start();
        try {
            BrevoMailer real = new BrevoMailer(RestClient.builder(),
                    "http://127.0.0.1:" + http.getAddress().getPort() + "/v3/smtp/email",
                    "xkeysib-test", "portal@gmail.com", "Portal");
            real.send("a@gmail.com", "Asha", CONTENT);
        } finally {
            http.stop(0);
        }
        assertThat(encoding.get()).isNull();
        assertThat(length.get()).isNotNull();
        assertThat(Integer.parseInt(length.get())).isPositive();
    }

    @Test
    void unconfiguredSendsNothing() {
        BrevoMailer blank = new BrevoMailer(RestClient.builder().baseUrl(URL).build(), "", "portal@gmail.com", "");

        assertThat(blank.isConfigured()).isFalse();
        assertThat(blank.senderName()).isEqualTo("MSRIT Backlog Portal");
        assertThatThrownBy(() -> blank.send("a@gmail.com", "Asha", CONTENT))
                .isInstanceOf(MailFailure.class)
                .satisfies(e -> assertThat(((MailFailure) e).kind()).isEqualTo(MailFailure.Kind.UNAVAILABLE));
    }
}
