package com.aiadvent.backend.chat.token;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.StringUtils;

public final class RedisTokenUsageCache implements TokenUsageCache {

  private static final Logger log = LoggerFactory.getLogger(RedisTokenUsageCache.class);

  private final StringRedisTemplate redisTemplate;
  private final ValueOperations<String, String> valueOperations;
  private final Duration ttl;
  private final TokenUsageMetrics metrics;

  public RedisTokenUsageCache(StringRedisTemplate redisTemplate, Duration ttl, TokenUsageMetrics metrics) {
    this.redisTemplate = redisTemplate;
    this.valueOperations = redisTemplate.opsForValue();
    this.ttl = ttl;
    this.metrics = metrics;
  }

  @Override
  public Integer get(String key) {
    if (!StringUtils.hasText(key)) {
      return null;
    }
    long start = System.nanoTime();
    try {
      String value = valueOperations.get(key);
      if (!StringUtils.hasText(value)) {
        record("miss", start);
        return null;
      }
      Integer result = Integer.valueOf(value);
      record("hit", start);
      return result;
    } catch (RuntimeException exception) {
      log.warn("Failed to fetch token count from Redis cache", exception);
      record("error", start);
      return null;
    }
  }

  @Override
  public void put(String key, int value) {
    if (!StringUtils.hasText(key)) {
      return;
    }
    long start = System.nanoTime();
    try {
      if (ttl != null) {
        valueOperations.set(key, Integer.toString(value), ttl);
      } else {
        valueOperations.set(key, Integer.toString(value));
      }
      record("write", start);
    } catch (RuntimeException exception) {
      log.warn("Failed to store token count in Redis cache", exception);
      record("write_error", start);
    }
  }

  private void record(String result, long startNanos) {
    if (metrics == null) {
      return;
    }
    long duration = System.nanoTime() - startNanos;
    metrics.recordRedisRequest(result, duration);
  }
}
