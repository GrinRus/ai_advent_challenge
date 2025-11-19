package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.config.ProfileDevAuthProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.profile.dev", name = "enabled", havingValue = "true")
public class DevProfileLinkService {

  private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 8;

  private final Cache<String, DevProfileLink> cache;
  private final SecureRandom random = new SecureRandom();
  private final Duration ttl;

  public DevProfileLinkService(ProfileDevAuthProperties properties) {
    this.ttl = properties.getLinkTtl();
    long seconds = Math.max(ttl.toSeconds(), 60);
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(seconds, TimeUnit.SECONDS)
            .build();
  }

  public DevLinkResponse issueLink(UserProfileDocument profile, @Nullable String channel) {
    Instant expiresAt = Instant.now().plus(ttl);
    String code = generateCode();
    DevProfileLink entry =
        new DevProfileLink(
            code,
            profile.profileId(),
            profile.namespace(),
            profile.reference(),
            normalizeChannel(channel),
            expiresAt);
    cache.put(code, entry);
    return new DevLinkResponse(
        entry.code(),
        entry.profileId(),
        entry.namespace(),
        entry.reference(),
        entry.channel(),
        entry.expiresAt());
  }

  private String generateCode() {
    StringBuilder builder = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      int index = random.nextInt(CODE_ALPHABET.length());
      builder.append(CODE_ALPHABET.charAt(index));
    }
    return builder.toString();
  }

  private String normalizeChannel(@Nullable String channel) {
    if (channel == null || channel.isBlank()) {
      return null;
    }
    return channel.trim().toLowerCase(Locale.ROOT);
  }

  private record DevProfileLink(
      String code,
      UUID profileId,
      String namespace,
      String reference,
      String channel,
      Instant expiresAt) {}
}
