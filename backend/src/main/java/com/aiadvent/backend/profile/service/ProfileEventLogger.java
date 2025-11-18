package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.UserIdentity;
import com.aiadvent.backend.profile.domain.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProfileEventLogger {

  private static final Logger log = LoggerFactory.getLogger(ProfileEventLogger.class);

  public void profileCreated(UserProfile profile) {
    if (profile == null) {
      return;
    }
    log.info(
        "profile_created profileId={} namespace={} reference={}",
        profile.getId(),
        profile.getNamespace(),
        profile.getReference());
  }

  public void profileUpdated(UserProfile profile) {
    if (profile == null) {
      return;
    }
    log.info(
        "profile_updated profileId={} namespace={} reference={} version={}",
        profile.getId(),
        profile.getNamespace(),
        profile.getReference(),
        profile.getVersion());
  }

  public void identityAttached(UserProfile profile, UserIdentity identity) {
    if (profile == null || identity == null) {
      return;
    }
    log.info(
        "identity_attached profileId={} provider={} externalId={}",
        profile.getId(),
        identity.getProvider(),
        identity.getExternalId());
  }

  public void identityDetached(UserProfile profile, String provider, String externalId) {
    if (profile == null) {
      return;
    }
    log.info(
        "identity_detached profileId={} provider={} externalId={}",
        profile.getId(),
        provider,
        externalId);
  }
}
