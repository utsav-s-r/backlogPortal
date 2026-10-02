package com.college.backlog.service.reminder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderEmailsTest {

    @Test
    void framesTheAdminMessageWithGreetingSubjectsAndFooter() {
        ReminderEmails.Content c = ReminderEmails.compose("  Exam on 5 Oct ", "Hall B, 9 am.\n",
                "Asha Rao", "1MS24CS001", "June 2026 Cycle", List.of("Data Structures (22CS44)"));

        assertThat(c.subject()).isEqualTo("Exam on 5 Oct");
        assertThat(c.text())
                .startsWith("Dear Asha Rao (1MS24CS001),\n\nHall B, 9 am.\n\n")
                .contains("Your verified backlog subjects for June 2026 Cycle:\n  - Data Structures (22CS44)\n")
                .endsWith(ReminderEmails.FOOTER + "\n");
    }

    @Test
    void aMissingNameFallsBackToTheUsnAndTheSubjectIsOneLine() {
        ReminderEmails.Content c = ReminderEmails.compose("Hall\r\ntickets", "m", null, "1MS24CS001", "C", List.of());

        assertThat(c.text()).startsWith("Dear 1MS24CS001 (1MS24CS001),");
        assertThat(c.subject()).isEqualTo("Hall tickets");
    }

    @Test
    void htmlPartIsTheTextEscaped() {
        ReminderEmails.Content c = ReminderEmails.compose("s", "<script>alert(1)</script> & more",
                "A", "1MS24CS001", "C", List.of());

        assertThat(c.html()).doesNotContain("<script>").contains("&lt;script&gt;").contains("&amp; more");
        assertThat(c.text()).contains("(none listed)");
    }
}
