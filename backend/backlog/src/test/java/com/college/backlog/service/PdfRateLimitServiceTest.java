package com.college.backlog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test — no Spring context, no Redis. The @Value fields are set directly. The TTL rules
 * live in {@code scripts/pdf-rate-limit.lua}, which a mock cannot execute; these tests pin what
 * the Java side sends and how it reads the reply.
 */
class PdfRateLimitServiceTest {

    private static final String WINDOW_MS = "3600000";

    private StringRedisTemplate redis;
    private PdfRateLimitService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        service = new PdfRateLimitService(redis);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxPerWindow", 3);
        ReflectionTestUtils.setField(service, "windowMinutes", 60L);
    }

    private void stubCounts(String key, Long... counts) {
        when(redis.execute(eq(PdfRateLimitService.SCRIPT), eq(List.of(key)), eq(WINDOW_MS)))
                .thenReturn(counts[0], Arrays.copyOfRange(counts, 1, counts.length));
    }

    @Test
    void allowsUpToTheLimitThenBlocks() {
        stubCounts("rl:pdf:alice", 1L, 2L, 3L, 4L);

        assertThat(service.tryConsume("alice")).isTrue();
        assertThat(service.tryConsume("alice")).isTrue();
        assertThat(service.tryConsume("alice")).isTrue();
        assertThat(service.tryConsume("alice")).isFalse();
    }

    @Test
    void countAndTtlGoThroughOneScriptCallNeverSeparateCommands() {
        // INCR and EXPIRE as two round trips is the bug: an EXPIRE failing after INCR=1 strands a
        // TTL-less key that blocks the user forever once the count passes the limit.
        stubCounts("rl:pdf:alice", 1L);

        service.tryConsume("alice");

        verify(redis).execute(PdfRateLimitService.SCRIPT, List.of("rl:pdf:alice"), WINDOW_MS);
        verify(redis, never()).opsForValue();
        verify(redis, never()).expire(any(), any(Duration.class));
    }

    @Test
    void scriptOnlySetsATtlWhereNoneExists() throws Exception {
        // The script's source is the whole fix, and no unit test can run Lua. Pin the two clauses
        // that matter: a TTL is set on PTTL -1 (new OR stranded key), never re-armed otherwise.
        String lua = new ClassPathResource("scripts/pdf-rate-limit.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(lua).contains("redis.call('PTTL', KEYS[1]) == -1")
                       .contains("redis.call('PEXPIRE', KEYS[1], ARGV[1])")
                       .doesNotContain("count == 1");
        assertThat(PdfRateLimitService.SCRIPT.getResultType()).isEqualTo(Long.class);
    }

    @Test
    void failsOpenWhenRedisThrows() {
        // A rate limiter is a guard, not a dependency: an unreachable Upstash must not lock a
        // student out of their own registration form. The service logs a WARN so it is not silent.
        when(redis.execute(eq(PdfRateLimitService.SCRIPT), anyList(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("upstash down"));

        assertThat(service.tryConsume("alice")).isTrue();
    }

    @Test
    void failsOpenWhenScriptReturnsNull() {
        when(redis.execute(eq(PdfRateLimitService.SCRIPT), anyList(), any())).thenReturn(null);

        assertThat(service.tryConsume("alice")).isTrue();
    }

    @Test
    void disabledShortCircuitsWithoutTouchingRedis() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.tryConsume("alice")).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void missingPrincipalIsAllowedAndNeverKeyedOnNull() {
        // Both PDF endpoints require authentication, so this is defence in depth: never build a
        // shared "rl:pdf:null" bucket that every caller would collide in.
        assertThat(service.tryConsume(null)).isTrue();
        assertThat(service.tryConsume("  ")).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void eachPrincipalGetsItsOwnBucket() {
        stubCounts("rl:pdf:alice", 4L);
        stubCounts("rl:pdf:bob", 1L);

        assertThat(service.tryConsume("alice")).isFalse();
        assertThat(service.tryConsume("bob")).isTrue();
    }

    @Test
    void retryAfterIsTheWholeWindowInSeconds() {
        assertThat(service.retryAfterSeconds()).isEqualTo(3600L);
    }
}
