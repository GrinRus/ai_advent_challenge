package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.Role;
import com.aiadvent.backend.profile.domain.UserIdentity;
import com.aiadvent.backend.profile.domain.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ProfileEventLogger {

  private static final Logger log = LoggerFactory.getLogger(ProfileEventLogger.class);
  private static final String SOURCE_PROFILE = "profile_api";
  private static final String SOURCE_IDENTITY = "identity_api";
  private static final String SOURCE_ROLE = "role_admin";

  private final ProfileAuditService auditService;

  public ProfileEventLogger(ProfileAuditService auditService) {
    this.auditService = auditService;
  }

  public void profileCreated(UserProfile profile, @Nullable String channel) {
    if (profile == null) {
      return;
    }
    log.info(
        "profile_created profileId={} namespace={} reference={}",
        profile.getId(),
        profile.getNamespace(),
        profile.getReference());
    auditService.recordEvent(
        profile,
        "profile_created",
        channel,
        SOURCE_PROFILE,
        metadata -> metadata.put("version", profile.getVersion()));
  }

  public void profileUpdated(UserProfile profile, @Nullable String channel) {
    if (profile == null) {
      return;
    }
    log.info(
        "profile_updated profileId={} namespace={} reference={} version={}",
        profile.getId(),
        profile.getNamespace(),
        profile.getReference(),
        profile.getVersion());
    auditService.recordEvent(
        profile,
        "profile_updated",
        channel,
        SOURCE_PROFILE,
        metadata -> metadata.put("version", profile.getVersion()));
  }

  public void identityAttached(UserProfile profile, UserIdentity identity, @Nullable String channel) {
    if (profile == null || identity == null) {
      return;
    }
    log.info(
        "identity_attached profileId={} provider={} externalId={}",
        profile.getId(),
        identity.getProvider(),
        identity.getExternalId());
    auditService.recordEvent(
        profile,
        "identity_attached",
        channel,
        SOURCE_IDENTITY,
        metadata -> {
          metadata.put("provider", identity.getProvider());
          metadata.put("externalId", identity.getExternalId());
        });
  }

  public void identityDetached(
      UserProfile profile, String provider, String externalId, @Nullable String channel) {
    if (profile == null) {
      return;
    }
    log.info(
        "identity_detached profileId={} provider={} externalId={}",
        profile.getId(),
        provider,
        externalId);
    auditService.recordEvent(
        profile,
        "identity_detached",
        channel,
        SOURCE_IDENTITY,
        metadata -> {
          metadata.put("provider", provider);
          metadata.put("externalId", externalId);
        });
  }

  public void roleAssigned(UserProfile profile, Role role) {
    if (profile == null || role == null) {
      return;
    }
    log.info("role_assigned profileId={} role={}", profile.getId(), role.getCode());
    auditService.recordEvent(
        profile,
        "role_assigned",
        null,
        SOURCE_ROLE,
        metadata -> metadata.put("role", role.getCode()));
  }

  public void roleRevoked(UserProfile profile, Role role) {
    if (profile == null || role == null) {
      return;
    }
    log.info("role_revoked profileId={} role={}", profile.getId(), role.getCode());
    auditService.recordEvent(
        profile,
        "role_revoked",
        null,
        SOURCE_ROLE,
        metadata -> metadata.put("role", role.getCode()));
  }
}
