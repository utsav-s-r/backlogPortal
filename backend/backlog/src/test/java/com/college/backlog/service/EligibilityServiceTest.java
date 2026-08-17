package com.college.backlog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityServiceTest {

    private final EligibilityService service = new EligibilityService();

    @Test
    void windowSpansEntryThroughCurrentForEverySemester() {
        // the real-world set: currentSemester is even by Semesters.isCurrentSemester
        assertThat(service.eligibleSemesters(2, 1)).containsExactlyInAnyOrder(1, 2);
        assertThat(service.eligibleSemesters(4, 1)).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(service.eligibleSemesters(6, 1)).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        assertThat(service.eligibleSemesters(8, 1)).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void oddCurrentSemesterStillResolves() {
        // parity is validated at the write path, not here — this stays a total function so a
        // legacy odd row reads sensibly instead of yielding an empty window and a hard block
        assertThat(service.eligibleSemesters(1, 1)).containsExactlyInAnyOrder(1);
        assertThat(service.eligibleSemesters(5, 1)).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void noBacklogIsRetiredByMovingUpAYear() {
        // the superseded rule floored sem 8 at 5; a first-year backlog must now stay reachable
        assertThat(service.isEligible(8, 1, 1)).isTrue();
        assertThat(service.isEligible(8, 1, 2)).isTrue();
        assertThat(service.isEligible(6, 1, 1)).isTrue();
    }

    @Test
    void outOfRangeCurrentSemesterYieldsEmpty() {
        assertThat(service.eligibleSemesters(0, 1)).isEmpty();
        assertThat(service.eligibleSemesters(9, 1)).isEmpty();
        assertThat(service.eligibleSemesters(-3, 1)).isEmpty();
    }

    @Test
    void isEligibleReflectsTheWindow() {
        // 3rd year (sem 6), normal intake: everything studied so far, nothing ahead
        assertThat(service.isEligible(6, 1, 3)).isTrue();
        assertThat(service.isEligible(6, 1, 6)).isTrue();
        assertThat(service.isEligible(6, 1, 7)).isFalse();
    }

    @Test
    void entrySemesterRaisesTheFloorForLateralEntrants() {
        // migrant who joined at sem 3, now sem 6: never offered sems 1-2
        assertThat(service.eligibleSemesters(6, 3)).containsExactlyInAnyOrder(3, 4, 5, 6);
        // joined at sem 3, now in sem 4: {3,4}
        assertThat(service.eligibleSemesters(4, 3)).containsExactlyInAnyOrder(3, 4);
        // joined at sem 7, now in sem 8: {7,8}
        assertThat(service.eligibleSemesters(8, 7)).containsExactlyInAnyOrder(7, 8);
        // entry equal to current: only that one semester
        assertThat(service.eligibleSemesters(5, 5)).containsExactlyInAnyOrder(5);
    }

    @Test
    void entryAwareIsEligibleExcludesPreEntrySemesters() {
        // entry 3, current 6: sem 2 excluded, sem 3 allowed
        assertThat(service.isEligible(6, 3, 2)).isFalse();
        assertThat(service.isEligible(6, 3, 3)).isTrue();
        assertThat(service.isEligible(6, 3, 6)).isTrue();
    }
}
