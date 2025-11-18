package com.aiadvent.backend.profile.service;

import java.util.Locale;
import org.springframework.util.StringUtils;

public record ProfileLookupKey(String namespace, String reference, String channel) {

  public ProfileLookupKey {
    if (!StringUtils.hasText(namespace) || !StringUtils.hasText(reference)) {
      throw new IllegalArgumentException("namespace and reference must not be blank");
    }
  }

  public String normalizedNamespace() {
    return normalize(namespace);
  }

  public String normalizedReference() {
    return normalize(reference);
  }

  public String normalizedChannel() {
    return channel != null ? normalize(channel) : null;
  }

  public String cacheKey() {
    return normalizedNamespace() + ":" + normalizedReference();
  }

  private String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
