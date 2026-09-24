package com.college.backlog.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The external cron's door, through the real filter chain: no session, no CSRF token, only
 * X-Cron-Token. Mail stays unconfigured here, so an accepted trigger touches no database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.reminders.cron-token=" + ReminderTriggerTest.TOKEN)
class ReminderTriggerTest {

    static final String TOKEN = "0123456789abcdef0123456789abcdef-test";
    private static final String RUN = "/api/internal/reminders/run";

    @Autowired private MockMvc mockMvc;

    @Test
    void theRightTokenIsAcceptedWithoutASessionOrCsrf() throws Exception {
        mockMvc.perform(post(RUN).header("X-Cron-Token", TOKEN)).andExpect(status().isAccepted());
    }

    @Test
    void aMissingOrWrongTokenIs401() throws Exception {
        mockMvc.perform(post(RUN)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(RUN).header("X-Cron-Token", TOKEN + "x")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(RUN).header("X-Cron-Token", "")).andExpect(status().isUnauthorized());
    }

    @Test
    void aShortTokenRefusesToBoot() {
        ReminderTriggerController c = new ReminderTriggerController();
        ReflectionTestUtils.setField(c, "cronToken", "too-short");
        assertThatThrownBy(c::validateToken).isInstanceOf(IllegalStateException.class);
    }
}
