package com.college.backlog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsnTest {

    @Test
    void validAcceptsWellFormedUsnAndRejectsMalformed() {
        assertThat(Usn.isValid("1MS22CS001")).isTrue();
        assertThat(Usn.isValid("1ms22cs001")).isFalse();   // prefix is case-sensitive
        assertThat(Usn.isValid("1MS22CS01")).isFalse();    // too few serial digits
        assertThat(Usn.isValid("1MS2CS001")).isFalse();    // too few year digits
        assertThat(Usn.isValid("2MS22CS001")).isFalse();   // wrong campus prefix
        assertThat(Usn.isValid(" 1MS22CS001")).isFalse();  // no trimming
        assertThat(Usn.isValid("")).isFalse();
        assertThat(Usn.isValid(null)).isFalse();
    }

    @Test
    void branchCodeIsUppercasedOrNullWhenMalformed() {
        assertThat(Usn.branchCode("1MS22cs001")).isEqualTo("CS");
        assertThat(Usn.branchCode("1MS24EC123")).isEqualTo("EC");
        assertThat(Usn.branchCode("bad")).isNull();
        assertThat(Usn.branchCode(null)).isNull();
    }

    @Test
    void admissionYearExpandsTwoDigitsOrMinusOneWhenMalformed() {
        assertThat(Usn.admissionYear("1MS22CS001")).isEqualTo(2022);
        assertThat(Usn.admissionYear("1MS05CS001")).isEqualTo(2005);
        assertThat(Usn.admissionYear("bad")).isEqualTo(-1);
        assertThat(Usn.admissionYear(null)).isEqualTo(-1);
    }
}
