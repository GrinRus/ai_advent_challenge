package com.aiadvent.backend.flow.memory;

import com.aiadvent.backend.flow.domain.FlowMemorySummary;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
  private final FlowMemorySummaryRepository flowMemorySummaryRepository;
  private final ObjectMapper objectMapper;
  private static final int MAX_VERSIONS_PER_CHANNEL = 10;
  private static final Duration RETENTION_TTL = Duration.ofDays(30);

  public FlowMemoryService(
      FlowSessionRepository flowSessionRepository,
      FlowMemoryVersionRepository flowMemoryVersionRepository,
      FlowMemorySummaryRepository flowMemorySummaryRepository,
      ObjectMapper objectMapper) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowMemoryVersionRepository = flowMemoryVersionRepository;
    this.flowMemorySummaryRepository = flowMemorySummaryRepository;
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

    List<FlowMemorySummary> summaries =
        flowMemorySummaryRepository.findByFlowSessionAndChannelOrderBySourceVersionStart(session, channel);
    long summarizedUntil =
        summaries.stream().mapToLong(FlowMemorySummary::getSourceVersionEnd).max().orElse(0L);

    List<FlowMemoryVersion> orderedVersions =
        summarizedUntil > 0
            ? flowMemoryVersionRepository
                .findByFlowSessionAndChannelAndVersionGreaterThanOrderByVersionAsc(
                    session, channel, summarizedUntil)
            : flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, channel);

    List<FlowMemoryVersion> tail = orderedVersions;
    if (limit > 0 && orderedVersions.size() > limit) {
      tail = orderedVersions.subList(orderedVersions.size() - limit, orderedVersions.size());
    }

    List<JsonNode> result = new ArrayList<>(summaries.size() + tail.size());
    summaries.stream().map(this::toSummaryNode).forEach(result::add);
    tail.stream().map(FlowMemoryVersion::getData).forEach(result::add);
    return result;
  }

  @Override
  @Transactional
  public FlowMemoryVersion append(
      UUID flowSessionId, String channel, Object payload, UUID createdByStepId) {
    JsonNode node = objectMapper.valueToTree(payload);
    return append(flowSessionId, channel, node, FlowMemoryMetadata.builder().createdByStepId(createdByStepId).build());
  }

  @Override
  @Transactional
  public FlowMemoryVersion append(
      UUID flowSessionId, String channel, JsonNode payload, UUID createdByStepId) {
    return append(
        flowSessionId,
        channel,
        payload,
        FlowMemoryMetadata.builder().createdByStepId(createdByStepId).build());
  }

  public FlowMemoryVersion append(
      UUID flowSessionId, String channel, JsonNode payload, FlowMemoryMetadata metadata) {
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
    if (metadata != null) {
      entity.setCreatedByStepId(metadata.createdByStepId());
      entity.setSourceType(metadata.sourceType());
      entity.setStepId(metadata.stepId());
      entity.setStepAttempt(metadata.stepAttempt());
      entity.setAgentVersionId(metadata.agentVersionId());
    }

    FlowMemoryVersion saved = flowMemoryVersionRepository.save(entity);
    session.setCurrentMemoryVersion(nextVersion);
    enforceRetention(session, channel, nextVersion);
    return saved;
  }

  private JsonNode toSummaryNode(FlowMemorySummary summary) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "summary");
    node.put("channel", summary.getChannel());
    node.put("content", summary.getSummaryText());
    node.put("sourceVersionStart", summary.getSourceVersionStart());
    node.put("sourceVersionEnd", summary.getSourceVersionEnd());
    if (summary.getTokenCount() != null) {
      node.put("tokenCount", summary.getTokenCount());
    }
    if (summary.getLanguage() != null) {
      node.put("language", summary.getLanguage());
    }
    if (summary.getStepId() != null) {
      node.put("stepId", summary.getStepId());
    }
    if (summary.getAttemptStart() != null) {
      node.put("attemptStart", summary.getAttemptStart());
    }
    if (summary.getAttemptEnd() != null) {
      node.put("attemptEnd", summary.getAttemptEnd());
    }
    if (summary.getMetadata() != null && !summary.getMetadata().isNull()) {
      node.set("metadata", summary.getMetadata());
    }
    return node;
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
