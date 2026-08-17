package com.college.backlog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemestersTest {

    @Test
    void studiableRangeIsUnchangedByParity() {
        // load-bearing: Subject.semester and StudentSemesterTerm.semester stay 1..8, so a sem-2
        // student's sem-1 backlogs remain registrable
        for (int sem = 1; sem <= 8; sem++) {
            assertThat(Semesters.isStudiable(sem)).as("sem %d studiable", sem).isTrue();
        }
        assertThat(Semesters.isStudiable(0)).isFalse();
        assertThat(Semesters.isStudiable(9)).isFalse();
    }

    @Test
    void currentSemesterIsEvenTwoThroughEight() {
        assertThat(Semesters.isCurrentSemester(2)).isTrue();
        assertThat(Semesters.isCurrentSemester(4)).isTrue();
        assertThat(Semesters.isCurrentSemester(6)).isTrue();
        assertThat(Semesters.isCurrentSemester(8)).isTrue();
        assertThat(Semesters.isCurrentSemester(1)).isFalse();
        assertThat(Semesters.isCurrentSemester(7)).isFalse();
        assertThat(Semesters.isCurrentSemester(10)).isFalse(); // even but out of range
        assertThat(Semesters.isCurrentSemester(0)).isFalse();
    }

    @Test
    void entrySemesterIsOddOneThroughSeven() {
        assertThat(Semesters.isEntrySemester(1)).isTrue();
        assertThat(Semesters.isEntrySemester(3)).isTrue();
        assertThat(Semesters.isEntrySemester(5)).isTrue();
        assertThat(Semesters.isEntrySemester(7)).isTrue();
        assertThat(Semesters.isEntrySemester(2)).isFalse();
        assertThat(Semesters.isEntrySemester(8)).isFalse();
        assertThat(Semesters.isEntrySemester(9)).isFalse(); // odd but out of range
        assertThat(Semesters.isEntrySemester(-1)).isFalse();
    }

    @Test
    void assertsThrowIllegalArgumentWithAnActionableMessage() {
        assertThatThrownBy(() -> Semesters.assertCurrentSemester(3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2, 4, 6 or 8");
        assertThatThrownBy(() -> Semesters.assertEntrySemester(4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1, 3, 5 or 7");
        assertThat(Semesters.isStudiable(3)).isTrue(); // still studiable, just not a CURRENT semester
    }

    @Test
    void progressionByTwoPreservesParity() {
        for (int sem = 2; sem <= 6; sem += 2) {
            assertThat(Semesters.isCurrentSemester(sem + 2)).as("%d + 2", sem).isTrue();
        }
    }
}
