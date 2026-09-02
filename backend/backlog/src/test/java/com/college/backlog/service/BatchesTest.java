package com.college.backlog.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchesTest {

    @Test
    void theLimitItselfIsAllowed() {
        // the cap is "more than MAX_ROWS", not "MAX_ROWS or more" — an off-by-one silently rejects
        // a batch of exactly the documented size
        assertThatCode(() -> Batches.assertWithinLimit(Batches.MAX_ROWS, "rows")).doesNotThrowAnyException();
        assertThatCode(() -> Batches.assertWithinLimit(0, "rows")).doesNotThrowAnyException();
    }

    @Test
    void oneOverTheLimitIsRefusedAsA400() {
        // 400, not the 500 a bare IllegalArgumentException would give — GlobalExceptionHandler
        // deliberately does not map IAE centrally, since NumberFormatException extends it
        assertThatThrownBy(() -> Batches.assertWithinLimit(Batches.MAX_ROWS + 1, "rows"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void theMessageNamesBothTheLimitAndWhatWasSent() {
        // the count sent is what the operator needs in order to know how far to split
        assertThatThrownBy(() -> Batches.assertWithinLimit(1340, "rows"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("At most 500 rows")
            .hasMessageContaining("you sent 1340")
            .hasMessageContaining("already saved is skipped");
    }

    @Test
    void theNounIsTheCallersWord() {
        assertThatThrownBy(() -> Batches.assertWithinLimit(501, "students"))
            .hasMessageContaining("500 students per batch");
    }
}
