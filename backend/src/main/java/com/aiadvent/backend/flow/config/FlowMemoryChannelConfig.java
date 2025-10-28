package com.aiadvent.backend.flow.config;

import java.time.Duration;
import java.util.Objects;

public record FlowMemoryChannelConfig(String channel, int retentionVersions, Duration retentionTtl) {

  public static final int DEFAULT_RETENTION_VERSIONS = 10;
  public static final Duration DEFAULT_RETENTION_TTL = Duration.ofDays(30);

  public FlowMemoryChannelConfig {
    String normalized = normalize(channel);
    if (normalized == null) {
      throw new IllegalArgumentException("Memory channel identifier must not be blank");
    }
    channel = normalized;
    retentionVersions = retentionVersions > 0 ? retentionVersions : DEFAULT_RETENTION_VERSIONS;
    retentionTtl = retentionTtl != null && !retentionTtl.isNegative()
        ? retentionTtl
        : DEFAULT_RETENTION_TTL;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public static FlowMemoryChannelConfig defaults(String channel) {
    return new FlowMemoryChannelConfig(channel, DEFAULT_RETENTION_VERSIONS, DEFAULT_RETENTION_TTL);
  }

  public FlowMemoryChannelConfig merge(FlowMemoryChannelConfig override) {
    if (override == null) {
      return this;
    }
    if (!Objects.equals(normalize(override.channel), channel)) {
      return this;
    }
    int versions = override.retentionVersions > 0 ? override.retentionVersions : retentionVersions;
    Duration ttl =
        override.retentionTtl != null && !override.retentionTtl.isNegative()
            ? override.retentionTtl
            : retentionTtl;
    return new FlowMemoryChannelConfig(channel, versions, ttl);
  }
}
