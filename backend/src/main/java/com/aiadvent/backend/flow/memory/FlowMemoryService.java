package com.aiadvent.backend.flow.memory;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemoryChannel;
import com.aiadvent.backend.flow.config.FlowMemoryChannelConfig;
import com.aiadvent.backend.flow.config.FlowMemoryConfig;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class FlowMemoryService implements MemoryChannelReader, MemoryChannelWriter {

  private final FlowSessionRepository flowSessionRepository;
  private final FlowMemoryVersionRepository flowMemoryVersionRepository;
  private final FlowMemorySummaryRepository flowMemorySummaryRepository;
  private final ObjectMapper objectMapper;
  private final ConcurrentMap<UUID, Map<String, RetentionSettings>> retentionPolicies = new ConcurrentHashMap<>();
  private static final RetentionSettings DEFAULT_RETENTION =
      new RetentionSettings(
          FlowMemoryChannelConfig.DEFAULT_RETENTION_VERSIONS,
          FlowMemoryChannelConfig.DEFAULT_RETENTION_TTL);

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

  public void initializeSharedChannels(UUID flowSessionId, FlowMemoryConfig memoryConfig) {
    Assert.notNull(flowSessionId, "flowSessionId must not be null");
    retentionPolicies.put(flowSessionId, buildPolicy(memoryConfig));
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
    RetentionSettings retention = resolveRetention(session, channel);
    long minVersionToKeep = Math.max(1, latestVersion - retention.maxVersions() + 1);
    flowMemoryVersionRepository.deleteByFlowSessionAndChannelAndVersionLessThan(
        session, channel, minVersionToKeep);
    Instant cutoff = Instant.now().minus(retention.retentionTtl());
    flowMemoryVersionRepository.deleteByFlowSessionAndChannelAndCreatedAtBefore(
        session, channel, cutoff);
  }

  private RetentionSettings resolveRetention(FlowSession session, String channel) {
    Map<String, RetentionSettings> policy =
        retentionPolicies.computeIfAbsent(session.getId(), id -> buildPolicy(session));
    return policy.getOrDefault(normalizeChannel(channel), DEFAULT_RETENTION);
  }

  private Map<String, RetentionSettings> buildPolicy(FlowMemoryConfig config) {
    Map<String, RetentionSettings> policy = new ConcurrentHashMap<>();
    registerDefaultChannels(policy);
    if (config != null && config.sharedChannels() != null) {
      for (FlowMemoryChannelConfig channel : config.sharedChannels()) {
        registerChannel(policy, channel.channel(), channel.retentionVersions(), channel.retentionTtl());
      }
    }
    return policy;
  }

  private Map<String, RetentionSettings> buildPolicy(FlowSession session) {
    FlowMemoryConfig configFromBlueprint = extractPolicyFromBlueprint(session);
    return buildPolicy(configFromBlueprint);
  }

  private FlowMemoryConfig extractPolicyFromBlueprint(FlowSession session) {
    FlowBlueprint blueprint = session.getFlowDefinition() != null ? session.getFlowDefinition().getDefinition() : null;
    FlowBlueprintMemory memory = blueprint != null ? blueprint.memory() : null;
    if (memory == null || memory.sharedChannels() == null) {
      return FlowMemoryConfig.empty();
    }
    List<FlowMemoryChannelConfig> channels = new ArrayList<>();
    for (FlowBlueprintMemoryChannel channel : memory.sharedChannels()) {
      if (channel == null) {
        continue;
      }
      String id = channel.id();
      int versions =
          channel.retentionVersions() != null && channel.retentionVersions() > 0
              ? channel.retentionVersions()
              : FlowMemoryChannelConfig.DEFAULT_RETENTION_VERSIONS;
      Duration ttl =
          channel.retentionDays() != null && channel.retentionDays() > 0
              ? Duration.ofDays(channel.retentionDays())
              : FlowMemoryChannelConfig.DEFAULT_RETENTION_TTL;
      channels.add(new FlowMemoryChannelConfig(id, versions, ttl));
    }
    return new FlowMemoryConfig(channels);
  }

  private void registerDefaultChannels(Map<String, RetentionSettings> policy) {
    registerChannel(
        policy,
        FlowMemoryChannels.CONVERSATION,
        FlowMemoryChannelConfig.DEFAULT_RETENTION_VERSIONS,
        FlowMemoryChannelConfig.DEFAULT_RETENTION_TTL);
    registerChannel(
        policy,
        FlowMemoryChannels.SHARED,
        FlowMemoryChannelConfig.DEFAULT_RETENTION_VERSIONS,
        FlowMemoryChannelConfig.DEFAULT_RETENTION_TTL);
  }

  private void registerChannel(
      Map<String, RetentionSettings> policy, String channel, int maxVersions, Duration ttl) {
    String key = normalizeChannel(channel);
    if (key == null) {
      return;
    }
    policy.put(key, new RetentionSettings(maxVersions > 0 ? maxVersions : DEFAULT_RETENTION.maxVersions(),
        ttl != null && !ttl.isNegative() ? ttl : DEFAULT_RETENTION.retentionTtl()));
  }

  private String normalizeChannel(String channel) {
    if (channel == null) {
      return null;
    }
    String trimmed = channel.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.toLowerCase(java.util.Locale.ROOT);
  }

  private record RetentionSettings(int maxVersions, Duration retentionTtl) {}
}
