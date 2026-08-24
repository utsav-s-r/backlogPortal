package com.college.backlog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test — no Spring context, no Redis. The @Value fields are set directly. */
class PdfRateLimitServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private PdfRateLimitService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        service = new PdfRateLimitService(redis);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxPerWindow", 3);
        ReflectionTestUtils.setField(service, "windowMinutes", 60L);
    }

    @Test
    void allowsUpToTheLimitThenBlocks() {
        when(valueOps.increment("rl:pdf:alice")).thenReturn(1L, 2L, 3L, 4L);

        assertThat(service.tryConsume("alice")).isTrue();
        assertThat(service.tryConsume("alice")).isTrue();
        assertThat(service.tryConsume("alice")).isTrue();
        assertThat(service.tryConsume("alice")).isFalse();
    }

    @Test
    void ttlIsSetOnceWhenTheWindowOpensNotOnEveryHit() {
        // Re-arming the TTL on every request would turn the fixed window into a rolling one, so a
        // steady trickle of requests could hold the key alive forever.
        when(valueOps.increment("rl:pdf:alice")).thenReturn(1L, 2L, 3L);

        service.tryConsume("alice");
        service.tryConsume("alice");
        service.tryConsume("alice");

        verify(redis, times(1)).expire("rl:pdf:alice", Duration.ofMinutes(60));
    }

    @Test
    void failsOpenWhenRedisThrows() {
        // A rate limiter is a guard, not a dependency: an unreachable Upstash must not lock a
        // student out of their own registration form. The service logs a WARN so it is not silent.
        when(valueOps.increment(anyString()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("upstash down"));

        assertThat(service.tryConsume("alice")).isTrue();
    }

    @Test
    void failsOpenWhenIncrementReturnsNull() {
        when(valueOps.increment(anyString())).thenReturn(null);

        assertThat(service.tryConsume("alice")).isTrue();
    }

    @Test
    void disabledShortCircuitsWithoutTouchingRedis() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.tryConsume("alice")).isTrue();
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void missingPrincipalIsAllowedAndNeverKeyedOnNull() {
        // Both PDF endpoints require authentication, so this is defence in depth: never build a
        // shared "rl:pdf:null" bucket that every caller would collide in.
        assertThat(service.tryConsume(null)).isTrue();
        assertThat(service.tryConsume("  ")).isTrue();
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void eachPrincipalGetsItsOwnBucket() {
        when(valueOps.increment("rl:pdf:alice")).thenReturn(4L);
        when(valueOps.increment("rl:pdf:bob")).thenReturn(1L);

        assertThat(service.tryConsume("alice")).isFalse();
        assertThat(service.tryConsume("bob")).isTrue();
    }

    @Test
    void retryAfterIsTheWholeWindowInSeconds() {
        assertThat(service.retryAfterSeconds()).isEqualTo(3600L);
    }

    @Test
    void expireIsNeverCalledOnAKeyThatWasNotJustCreated() {
        when(valueOps.increment("rl:pdf:alice")).thenReturn(2L);

        service.tryConsume("alice");

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
