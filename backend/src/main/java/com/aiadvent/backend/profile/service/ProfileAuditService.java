package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.ProfileAuditEvent;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.persistence.ProfileAuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ProfileAuditService {

  private final ProfileAuditRepository repository;
  private final ObjectMapper objectMapper;

  public ProfileAuditService(ProfileAuditRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public void recordEvent(
      UserProfile profile,
      String eventType,
      @Nullable String channel,
      String source,
      @Nullable Consumer<ObjectNode> metadataCustomizer) {
    if (profile == null || eventType == null) {
      return;
    }
    ProfileAuditEvent event = new ProfileAuditEvent();
    event.setProfile(profile);
    event.setEventType(eventType);
    event.setSource(source);
    event.setChannel(normalizeChannel(channel));
    ObjectNode metadata = buildMetadata(profile);
    if (metadataCustomizer != null) {
      metadataCustomizer.accept(metadata);
    }
    event.setMetadata(metadata);
    repository.save(event);
  }

  public List<ProfileAuditDocument> findRecent(UUID profileId, int limit) {
    if (profileId == null) {
      return List.of();
    }
    int pageSize = Math.min(Math.max(limit, 1), 100);
    return repository.findByProfileIdOrderByCreatedAtDesc(profileId, PageRequest.of(0, pageSize)).
        stream()
        .map(ProfileAuditService::toDocument)
        .toList();
  }

  private static ProfileAuditDocument toDocument(ProfileAuditEvent event) {
    JsonNode metadata = event.getMetadata();
    Instant createdAt = event.getCreatedAt();
    return new ProfileAuditDocument(
        event.getId(),
        event.getEventType(),
        event.getSource(),
        event.getChannel(),
        metadata,
        createdAt);
  }

  private ObjectNode buildMetadata(UserProfile profile) {
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("namespace", profile.getNamespace());
    metadata.put("reference", profile.getReference());
    return metadata;
  }

  private String normalizeChannel(@Nullable String channel) {
    if (channel == null || channel.isBlank()) {
      return null;
    }
    return channel.trim().toLowerCase(Locale.ROOT);
  }
}
