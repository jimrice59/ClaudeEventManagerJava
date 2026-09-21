package com.eventmanager.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setMaxRequests(1000);
        properties.setWindowDuration(Duration.ofSeconds(1));
        rateLimiter = new RateLimiter(redisTemplate, properties);
    }

    @SuppressWarnings("unchecked")
    private void stubScriptResult(Long result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    @Test
    void isAllowed_returnsTrueWhenUnderLimit() {
        stubScriptResult(1L);

        assertThat(rateLimiter.isAllowed("ip:1.2.3.4")).isTrue();
    }

    @Test
    void isAllowed_returnsFalseWhenLimitExceeded() {
        stubScriptResult(0L);

        assertThat(rateLimiter.isAllowed("ip:1.2.3.4")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void isAllowed_failsOpenWhenRedisIsUnreachable() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThat(rateLimiter.isAllowed("ip:1.2.3.4")).isTrue();
    }
}
