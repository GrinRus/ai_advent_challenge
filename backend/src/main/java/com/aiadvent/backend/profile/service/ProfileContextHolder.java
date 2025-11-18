package com.aiadvent.backend.profile.service;

import java.util.Optional;

public final class ProfileContextHolder {

  private static final ThreadLocal<ProfileLookupKey> CONTEXT = new ThreadLocal<>();

  private ProfileContextHolder() {}

  public static void set(ProfileLookupKey key) {
    CONTEXT.set(key);
  }

  public static Optional<ProfileLookupKey> current() {
    return Optional.ofNullable(CONTEXT.get());
  }

  public static void clear() {
    CONTEXT.remove();
  }
}
