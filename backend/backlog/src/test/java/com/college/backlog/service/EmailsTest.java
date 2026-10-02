package com.college.backlog.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailsTest {

    private static final String USN = "1MS22CS001";

    @ParameterizedTest
    @ValueSource(strings = {"1ms22cs001@msrit.edu", "asha@gmail.com", "a.b+tag@outlook.co.in",
        "x@yahoo.com"})
    void acceptsAnyDomain(String email) {
        assertThat(Emails.normalize(email, USN)).isEqualTo(email);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankResetsToInstitutional(String email) {
        assertThat(Emails.normalize(email, USN)).isEqualTo("1ms22cs001@msrit.edu");
    }

    @ParameterizedTest
    @ValueSource(strings = {"asha", "asha@gmail", "@gmail.com", "asha@@gmail.com", "as ha@gmail.com",
        "asha@gmail.com x"})
    void rejectsMalformed(String email) {
        assertThatThrownBy(() -> Emails.normalize(email, USN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(Emails.MESSAGE);
    }

    @org.junit.jupiter.api.Test
    void capsLengthBelowTheColumn() {
        String atLimit = "a".repeat(Emails.MAX_LENGTH - "@gmail.com".length()) + "@gmail.com";
        assertThat(Emails.normalize(atLimit, USN)).isEqualTo(atLimit);
        assertThatThrownBy(() -> Emails.normalize("a" + atLimit, USN))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
