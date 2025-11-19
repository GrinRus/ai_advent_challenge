package com.aiadvent.backend.profile.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.profile.cache")
public class ProfileCacheProperties {

  private Duration localTtl = Duration.ofMinutes(5);
  private Duration redisTtl = Duration.ofMinutes(10);
  private long maximumSize = 5_000;
  private String redisPrefix = "profile:cache:";
  private String eventChannel = "profile:cache:events";
  private boolean redisEnabled = true;

  public Duration getLocalTtl() {
    return localTtl;
  }

  public void setLocalTtl(Duration localTtl) {
    this.localTtl = localTtl;
  }

  public Duration getRedisTtl() {
    return redisTtl;
  }

  public void setRedisTtl(Duration redisTtl) {
    this.redisTtl = redisTtl;
  }

  public long getMaximumSize() {
    return maximumSize;
  }

  public void setMaximumSize(long maximumSize) {
    this.maximumSize = maximumSize;
  }

  public String getRedisPrefix() {
    return redisPrefix;
  }

  public void setRedisPrefix(String redisPrefix) {
    this.redisPrefix = redisPrefix;
  }

  public String getEventChannel() {
    return eventChannel;
  }

  public void setEventChannel(String eventChannel) {
    this.eventChannel = eventChannel;
  }

  public boolean isRedisEnabled() {
    return redisEnabled;
  }

  public void setRedisEnabled(boolean redisEnabled) {
    this.redisEnabled = redisEnabled;
  }
}
