package com.aiadvent.backend.chat.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisTokenUsageCacheTest {

  @Test
  void getReturnsIntegerValueWhenPresent() {
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(valueOps.get("cache:key")).thenReturn("42");

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(valueOps);

    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    RedisTokenUsageCache cache = new RedisTokenUsageCache(template, Duration.ofSeconds(30), metrics);

    Integer result = cache.get("cache:key");

    assertThat(result).isEqualTo(42);
  }

  @Test
  void getReturnsNullWhenValueMissingOrBlank() {
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(valueOps.get("cache:key")).thenReturn(null);
    when(valueOps.get("cache:blank")).thenReturn(" ");

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(valueOps);

    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    RedisTokenUsageCache cache = new RedisTokenUsageCache(template, Duration.ofSeconds(30), metrics);

    assertThat(cache.get("cache:key")).isNull();
    assertThat(cache.get("cache:blank")).isNull();
  }

  @Test
  void getSwallowsRedisErrors() {
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(valueOps);

    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    RedisTokenUsageCache cache = new RedisTokenUsageCache(template, Duration.ofSeconds(5), metrics);

    assertThat(cache.get("cache:key")).isNull();
  }

  @Test
  void putStoresValueWithTtl() {
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(valueOps);

    Duration ttl = Duration.ofSeconds(45);
    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    RedisTokenUsageCache cache = new RedisTokenUsageCache(template, ttl, metrics);

    cache.put("cache:key", 15);

    verify(valueOps).set("cache:key", "15", ttl);
  }

  @Test
  void putStoresWithoutTtlWhenUnset() {
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(valueOps);

    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    RedisTokenUsageCache cache = new RedisTokenUsageCache(template, null, metrics);

    cache.put("cache:key", 21);

    verify(valueOps).set("cache:key", "21");
  }

  @Test
  void putSwallowsRedisErrors() {
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    doThrow(new RuntimeException("Redis unavailable")).when(valueOps).set(anyString(), anyString(), any(Duration.class));

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(valueOps);

    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    RedisTokenUsageCache cache = new RedisTokenUsageCache(template, Duration.ofSeconds(10), metrics);

    cache.put("cache:key", 10);

    verify(valueOps, times(1)).set("cache:key", "10", Duration.ofSeconds(10));
  }
}
