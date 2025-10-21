package com.aiadvent.backend.chat.config;

import com.aiadvent.backend.chat.token.DefaultTokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageCache;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageMetrics;
import com.aiadvent.backend.chat.token.RedisTokenUsageCache;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(TokenUsageProperties.class)
public class TokenUsageConfiguration {

  @Bean
  public EncodingRegistry encodingRegistry() {
    return Encodings.newDefaultEncodingRegistry();
  }

  @Bean
  public TokenUsageMetrics tokenUsageMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    return new TokenUsageMetrics(meterRegistry);
  }

  @Bean
  public TokenUsageCache tokenUsageCache(
      TokenUsageProperties properties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
      TokenUsageMetrics metrics) {
    TokenUsageProperties.Cache cacheProperties =
        Optional.ofNullable(properties.getCache()).orElseGet(TokenUsageProperties.Cache::new);
    if (!cacheProperties.isEnabled()) {
      return TokenUsageCache.noOp();
    }
    StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
    if (redisTemplate == null) {
      return TokenUsageCache.noOp();
    }
    Duration ttl = cacheProperties.getTtl();
    return new RedisTokenUsageCache(redisTemplate, ttl, metrics);
  }

  @Bean
  public TokenUsageEstimator tokenUsageEstimator(
      EncodingRegistry encodingRegistry, TokenUsageCache cache, TokenUsageProperties properties) {
    TokenUsageProperties.Cache cacheProperties =
        Optional.ofNullable(properties.getCache()).orElseGet(TokenUsageProperties.Cache::new);
    return new DefaultTokenUsageEstimator(
        encodingRegistry, cache, properties.getDefaultTokenizer(), cacheProperties.getKeyPrefix());
  }
}
