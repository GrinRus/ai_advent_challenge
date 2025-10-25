package com.aiadvent.backend.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.chat.token-usage")
@Validated
public class TokenUsageProperties {

  /**
   * Default tokenizer identifier used when provider configuration does not override it.
   * Accepts either {@code ModelType} or {@code EncodingType} names supported by jtokkit.
   */
  private String defaultTokenizer = "cl100k_base";

  private Cache cache = new Cache();
  private boolean lightweightMode = false;

  public String getDefaultTokenizer() {
    return defaultTokenizer;
  }

  public void setDefaultTokenizer(String defaultTokenizer) {
    this.defaultTokenizer = defaultTokenizer;
  }

  public Cache getCache() {
    return cache;
  }

  public void setCache(Cache cache) {
    this.cache = cache;
  }

  public boolean isLightweightMode() {
    return lightweightMode;
  }

  public void setLightweightMode(boolean lightweightMode) {
    this.lightweightMode = lightweightMode;
  }

  public static class Cache {

    /**
     * Enables Redis-backed caching of token counts. When disabled the estimator calculates tokens
     * for every request.
     */
    private boolean enabled = false;

    /**
     * TTL applied to cached token counts. Defaults to 15 minutes which keeps frequently used prompts
     * warm without retaining stale data for too long.
     */
    private Duration ttl = Duration.ofMinutes(15);

    /** Prefix appended to Redis keys that store cached token counts. */
    private String keyPrefix = "chat:usage";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getTtl() {
      return ttl;
    }

    public void setTtl(Duration ttl) {
      this.ttl = ttl;
    }

    public String getKeyPrefix() {
      return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
      this.keyPrefix = keyPrefix;
    }
  }
}
