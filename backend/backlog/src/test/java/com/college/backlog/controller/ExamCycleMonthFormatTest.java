package com.college.backlog.controller;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An exam cycle's month is stored canonically as {@code YYYY-MM}. It is shown in the admin table,
 * returned by the PUBLIC {@code /api/registration-status}, and read by the printed form, so free
 * text there is not untidiness — it is whatever someone typed, on a document a student signs.
 * Before this, the field carried no annotation at all and "Not a valid month/year" was storable.
 *
 * <p>Full context, not standalone MockMvc: {@code @Valid} does run standalone, but the controller
 * is ADMIN-only and {@code AccountExistenceFilter} 401s a principal with no {@code users} row, so
 * the 201 case needs a real seeded admin to prove anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExamCycleMonthFormatTest {

    private static final String CYCLES = "/api/admin/exam-cycles";
    private static final String ADMIN = "cycle-format-admin";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedTheAdmin() {
        User admin = new User();
        admin.setUsername(ADMIN);
        admin.setPassword(passwordEncoder.encode("irrelevant"));
        admin.setRole(UserRole.ADMIN);
        userRepository.save(admin);
    }

    private String body(String examMonthYear) {
        return examMonthYear == null
                ? "{\"name\":\"Format Cycle\"}"
                : "{\"name\":\"Format Cycle\",\"examMonthYear\":\"" + examMonthYear + "\"}";
    }

    /** Every spelling that was accepted before, plus the near-misses a typed year produces. */
    @ParameterizedTest
    @ValueSource(strings = {
        "June 2026",            // the placeholder's own example
        "9/20/2026",            // a date, not a month
        "Not a valid month/year",
        "2026-13",              // no such month
        "2026-00",
        "2026-6",               // unpadded
        "26-06",                // two-digit year
        "2026-06-01",           // a full date
        " 2026-06 ",            // untrimmed
    })
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesAnythingThatIsNotAYearAndMonth(String examMonthYear) throws Exception {
        mockMvc.perform(post(CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(examMonthYear)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("YYYY-MM")));

        assertThat(examCycleRepository.findAll()).isEmpty();
    }

    /** Absent is refused too: a cycle with no month prints a blank on the form. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesAMissingMonth() throws Exception {
        mockMvc.perform(post(CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("required")));
    }

    /** The control: without it every case above would pass on a rule that refused all of them. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void acceptsTheCanonicalForm() throws Exception {
        mockMvc.perform(post(CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("2026-06")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.examMonthYear").value("2026-06"));
    }
}
