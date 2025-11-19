package com.aiadvent.backend.profile.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProfileCacheInvalidator {

  private static final Logger log = LoggerFactory.getLogger(ProfileCacheInvalidator.class);

  private final UserProfileService userProfileService;
  @Nullable private final Counter invalidationCounter;

  public ProfileCacheInvalidator(
      UserProfileService userProfileService, @Nullable MeterRegistry meterRegistry) {
    this.userProfileService = userProfileService;
    this.invalidationCounter =
        meterRegistry != null
            ? meterRegistry.counter("user_profile_cache_invalidation_total")
            : null;
  }

  @EventListener
  public void onProfileChanged(ProfileChangedEvent event) {
    if (event == null
        || !StringUtils.hasText(event.namespace())
        || !StringUtils.hasText(event.reference())) {
      return;
    }
    try {
      userProfileService.evict(new ProfileLookupKey(event.namespace(), event.reference(), null));
      if (invalidationCounter != null) {
        invalidationCounter.increment();
      }
      if (log.isDebugEnabled()) {
        log.debug(
            "Invalidated profile cache for {}:{}", event.namespace(), event.reference());
      }
    } catch (IllegalArgumentException ex) {
      log.warn(
          "Failed to invalidate profile cache for {}:{} - {}",
          event.namespace(),
          event.reference(),
          ex.getMessage());
    }
  }
}
