package com.aiadvent.backend.flow.memory;

import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class FlowMemoryService implements MemoryChannelReader, MemoryChannelWriter {

  private final FlowSessionRepository flowSessionRepository;
  private final FlowMemoryVersionRepository flowMemoryVersionRepository;
  private final ObjectMapper objectMapper;
  private static final int MAX_VERSIONS_PER_CHANNEL = 10;
  private static final Duration RETENTION_TTL = Duration.ofDays(30);

  public FlowMemoryService(
      FlowSessionRepository flowSessionRepository,
      FlowMemoryVersionRepository flowMemoryVersionRepository,
      ObjectMapper objectMapper) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowMemoryVersionRepository = flowMemoryVersionRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<JsonNode> read(UUID flowSessionId, String channel) {
    Assert.notNull(flowSessionId, "flowSessionId must not be null");
    Assert.hasText(channel, "channel must not be empty");
    return flowSessionRepository
        .findById(flowSessionId)
        .flatMap(session -> flowMemoryVersionRepository
            .findFirstByFlowSessionAndChannelOrderByVersionDesc(session, channel)
            .map(FlowMemoryVersion::getData));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<JsonNode> read(UUID flowSessionId, String channel, long version) {
    Assert.notNull(flowSessionId, "flowSessionId must not be null");
    Assert.hasText(channel, "channel must not be empty");
    return flowSessionRepository
        .findById(flowSessionId)
        .flatMap(
            session ->
                flowMemoryVersionRepository
                    .findByFlowSessionAndChannelAndVersion(session, channel, version)
                    .map(FlowMemoryVersion::getData));
  }

  @Override
  @Transactional(readOnly = true)
  public List<JsonNode> history(UUID flowSessionId, String channel, int limit) {
    Assert.notNull(flowSessionId, "flowSessionId must not be null");
    Assert.hasText(channel, "channel must not be empty");
    if (limit < 0) {
      limit = Integer.MAX_VALUE;
    }
    FlowSession session =
        flowSessionRepository
            .findById(flowSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + flowSessionId));
    return flowMemoryVersionRepository
        .findByFlowSessionAndChannelOrderByVersionDesc(session, channel)
        .stream()
        .limit(limit)
        .map(FlowMemoryVersion::getData)
        .toList();
  }

  @Override
  @Transactional
  public FlowMemoryVersion append(
      UUID flowSessionId, String channel, Object payload, UUID createdByStepId) {
    JsonNode node = objectMapper.valueToTree(payload);
    return append(flowSessionId, channel, node, createdByStepId);
  }

  @Override
  @Transactional
  public FlowMemoryVersion append(
      UUID flowSessionId, String channel, JsonNode payload, UUID createdByStepId) {
    Assert.notNull(flowSessionId, "flowSessionId must not be null");
    Assert.hasText(channel, "channel must not be empty");
    Assert.notNull(payload, "payload must not be null");

    FlowSession session =
        flowSessionRepository
            .findByIdForUpdate(flowSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + flowSessionId));

    Optional<FlowMemoryVersion> latest =
        flowMemoryVersionRepository.findFirstByFlowSessionAndChannelOrderByVersionDesc(session, channel);

    long nextVersion = latest.map(existing -> existing.getVersion() + 1).orElse(1L);
    Long parentRecordId = latest.map(FlowMemoryVersion::getId).orElse(null);

    FlowMemoryVersion entity =
        new FlowMemoryVersion(session, channel, nextVersion, payload, parentRecordId);
   entity.setCreatedByStepId(createdByStepId);

    FlowMemoryVersion saved = flowMemoryVersionRepository.save(entity);
    session.setCurrentMemoryVersion(nextVersion);
    enforceRetention(session, channel, nextVersion);
    return saved;
  }

  private void enforceRetention(FlowSession session, String channel, long latestVersion) {
    long minVersionToKeep = Math.max(1, latestVersion - MAX_VERSIONS_PER_CHANNEL + 1);
    flowMemoryVersionRepository.deleteByFlowSessionAndChannelAndVersionLessThan(
        session, channel, minVersionToKeep);
    Instant cutoff = Instant.now().minus(RETENTION_TTL);
    flowMemoryVersionRepository.deleteByFlowSessionAndChannelAndCreatedAtBefore(
        session, channel, cutoff);
  }
}
