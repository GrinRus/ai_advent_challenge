package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserProfileDocument(
    UUID profileId,
    String namespace,
    String reference,
    String displayName,
    String locale,
    String timezone,
    UserProfile.CommunicationMode communicationMode,
    List<String> habits,
    List<String> antiPatterns,
    JsonNode workHours,
    JsonNode metadata,
    List<UserIdentityDocument> identities,
    List<UserChannelDocument> channels,
    List<String> roles,
    Instant updatedAt,
    long version) {

  public record UserIdentityDocument(
      String provider, String externalId, JsonNode attributes, List<String> scopes) {}

  public record UserChannelDocument(String channel, JsonNode settings) {}
}
