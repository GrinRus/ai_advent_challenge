package com.aiadvent.backend.chat.config;

import com.aiadvent.backend.chat.token.DefaultTokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageCache;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageMetrics;
import com.aiadvent.backend.chat.token.RedisTokenUsageCache;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.knuddels.jtokkit.api.ModelType;
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
  public EncodingRegistry encodingRegistry(TokenUsageProperties properties) {
    return properties.isLightweightMode()
        ? new LightweightEncodingRegistry()
        : Encodings.newDefaultEncodingRegistry();
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

  private static final class LightweightEncodingRegistry implements EncodingRegistry {
    private final Encoding encoding = new StubEncoding();

    @Override
    public Optional<Encoding> getEncoding(String encodingName) {
      return Optional.of(encoding);
    }

    @Override
    public Encoding getEncoding(EncodingType encodingType) {
      return encoding;
    }

    @Override
    public Optional<Encoding> getEncodingForModel(String modelName) {
      return Optional.of(encoding);
    }

    @Override
    public Encoding getEncodingForModel(ModelType modelType) {
      return encoding;
    }

    @Override
    public EncodingRegistry registerGptBytePairEncoding(
        com.knuddels.jtokkit.api.GptBytePairEncodingParams parameters) {
      return this;
    }

    @Override
    public EncodingRegistry registerCustomEncoding(Encoding encoding) {
      return this;
    }
  }

  private static final class StubEncoding implements Encoding {
    @Override
    public IntArrayList encode(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingResult encode(String text, int maxTokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IntArrayList encodeOrdinary(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingResult encodeOrdinary(String text, int maxTokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int countTokens(String text) {
      return length(text);
    }

    @Override
    public int countTokensOrdinary(String text) {
      return length(text);
    }

    @Override
    public String decode(IntArrayList tokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] decodeBytes(IntArrayList tokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
      return "lightweight-encoding";
    }

    private static int length(String value) {
      return value != null ? value.length() : 0;
    }
  }
}
