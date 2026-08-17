package com.college.backlog.service;

import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class AcademicYearsTest {

    @Test
    void acceptsTheInclusiveRangeFromMinYearThroughNextYear() {
        assertThat(AcademicYears.isInRange(AcademicYears.MIN_YEAR)).isTrue();
        assertThat(AcademicYears.isInRange(Year.now().getValue())).isTrue();
        // next year is settable so an upcoming catalog can be prepared ahead of time
        assertThat(AcademicYears.isInRange(Year.now().getValue() + 1)).isTrue();
    }

    @Test
    void rejectsOutsideThatRange() {
        assertThat(AcademicYears.isInRange(AcademicYears.MIN_YEAR - 1)).isFalse();
        assertThat(AcademicYears.isInRange(Year.now().getValue() + 2)).isFalse();
    }

    @Test
    void rejectsTheTwoValuesAnUnvalidatedPrimitiveProduces() {
        // 0 is what an omitted int field arrives as (and the subjects column default), which is
        // exactly what the no-op @NotNull on SubjectCreateRequest used to let through.
        assertThat(AcademicYears.isInRange(0)).isFalse();
        assertThat(AcademicYears.isInRange(-1)).isFalse();
        // and a far-future year, which CourseCodes.matchesYear happily accepts as "99..."
        assertThat(AcademicYears.isInRange(9999)).isFalse();
    }

    @Test
    void assertInRangeThrowsWithTheYearInTheMessage() {
        assertThatThrownBy(() -> AcademicYears.assertInRange(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0")
                .hasMessageContaining("out of range");
        assertThatCode(() -> AcademicYears.assertInRange(Year.now().getValue()))
                .doesNotThrowAnyException();
    }
}
