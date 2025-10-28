package com.aiadvent.backend.mcp.agentops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Component
class AgentOpsClient {

  private static final ParameterizedTypeReference<List<JsonNode>> LIST_NODE_REF =
      new ParameterizedTypeReference<>() {};
  private static final TypeReference<List<JsonNode>> LIST_NODE_TYPE = new TypeReference<>() {};

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  AgentOpsClient(WebClient agentOpsWebClient, ObjectMapper objectMapper) {
    this.webClient = agentOpsWebClient;
    this.objectMapper = objectMapper;
  }

  List<AgentSummary> listAgents(ListAgentsInput input) {
    List<JsonNode> nodes =
        webClient
            .get()
            .uri("/api/agents/definitions")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(LIST_NODE_REF)
            .timeout(java.time.Duration.ofSeconds(20))
            .onErrorMap(
                ex ->
                    new AgentOpsClientException(
                        "Failed to list agent definitions: " + ex.getMessage(), ex))
            .blockOptional()
            .orElse(List.of());

    return nodes.stream()
        .map(this::toSummary)
        .filter(summary -> filterSummary(summary, input))
        .sorted(Comparator.comparing(AgentSummary::updatedAt).reversed())
        .limit(
            input.limit() != null && input.limit() > 0
                ? input.limit().longValue()
                : Long.MAX_VALUE)
        .toList();
  }

  RegisterAgentResult registerAgent(RegisterAgentInput input) {
    if (!StringUtils.hasText(input.identifier()) || !StringUtils.hasText(input.displayName())) {
      throw new IllegalArgumentException("Both identifier and displayName are required");
    }
    if (!StringUtils.hasText(input.createdBy())) {
      throw new IllegalArgumentException("createdBy must be provided");
    }

    Map<String, Object> payload =
        Map.of(
            "identifier", input.identifier().trim(),
            "displayName", input.displayName().trim(),
            "description",
                StringUtils.hasText(input.description())
                    ? input.description().trim()
                    : null,
            "active", input.active() == null ? Boolean.TRUE : input.active(),
            "createdBy", input.createdBy().trim(),
            "updatedBy",
                StringUtils.hasText(input.updatedBy())
                    ? input.updatedBy().trim()
                    : input.createdBy().trim());

    JsonNode node =
        webClient
            .post()
            .uri("/api/agents/definitions")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(java.time.Duration.ofSeconds(20))
            .onErrorMap(
                ex ->
                    new AgentOpsClientException(
                        "Failed to register agent via backend: " + ex.getMessage(), ex))
            .block();

    if (node == null || node.isNull()) {
      throw new AgentOpsClientException("Backend returned empty response when creating agent");
    }

    return new RegisterAgentResult(
        valueAsUuid(node.get("id")),
        valueAsText(node.get("identifier")),
        valueAsText(node.get("displayName")),
        node.path("active").asBoolean(false),
        node.path("createdAt").asText(null),
        node.path("updatedAt").asText(null));
  }

  PreviewDependenciesResult previewDependencies(PreviewDependenciesInput input) {
    UUID definitionId =
        Optional.ofNullable(input.definitionId()).orElseGet(() -> resolveDefinitionId(input));

    JsonNode node =
        webClient
            .get()
            .uri("/api/agents/definitions/{id}", definitionId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(java.time.Duration.ofSeconds(20))
            .onErrorMap(
                ex ->
                    new AgentOpsClientException(
                        "Failed to load agent definition %s: %s"
                            .formatted(definitionId, ex.getMessage()),
                        ex))
            .block();

    if (node == null || node.isNull()) {
      throw new AgentOpsClientException(
          "Backend returned empty definition for id " + definitionId);
    }

    JsonNode versionsNode = node.path("versions");
    List<PreviewDependenciesResult.AgentVersionSnapshot> versions =
        versionsNode.isArray()
            ? toVersionSnapshots(versionsNode)
            : List.of();

    return new PreviewDependenciesResult(
        valueAsUuid(node.get("id")),
        valueAsText(node.get("identifier")),
        valueAsText(node.get("displayName")),
        node.path("active").asBoolean(false),
        versions);
  }

  private boolean filterSummary(AgentSummary summary, ListAgentsInput input) {
    if (input == null) {
      return true;
    }
    if (input.activeOnly() != null && input.activeOnly() && !summary.active()) {
      return false;
    }
    if (StringUtils.hasText(input.query())) {
      String query = input.query().trim().toLowerCase();
      return summary.identifier().toLowerCase().contains(query)
          || summary.displayName().toLowerCase().contains(query);
    }
    return true;
  }

  private AgentSummary toSummary(JsonNode node) {
    return new AgentSummary(
        valueAsUuid(node.get("id")),
        valueAsText(node.get("identifier")),
        valueAsText(node.get("displayName")),
        node.path("active").asBoolean(false),
        node.path("latestVersion").isMissingNode() ? null : node.get("latestVersion").asInt(),
        node.path("latestPublishedVersion").isMissingNode()
            ? null
            : node.get("latestPublishedVersion").asInt(),
        parseInstant(node.get("updatedAt")),
        parseInstant(node.get("createdAt")));
  }

  private List<PreviewDependenciesResult.AgentVersionSnapshot> toVersionSnapshots(JsonNode node) {
    return toStream(node).stream()
        .map(
            version -> {
              int versionNumber = version.path("version").asInt(-1);
              String status = version.path("status").asText("UNKNOWN");
              String providerId = version.path("providerId").asText(null);
              String modelId = version.path("modelId").asText(null);
              List<String> toolCodes = extractToolCodes(version.path("invocationOptions"));
              Instant createdAt = parseInstant(version.get("createdAt"));
              Instant publishedAt = parseInstant(version.get("publishedAt"));
              return new PreviewDependenciesResult.AgentVersionSnapshot(
                  versionNumber, status, providerId, modelId, toolCodes, createdAt, publishedAt);
            })
        .sorted(Comparator.comparingInt(PreviewDependenciesResult.AgentVersionSnapshot::version).reversed())
        .toList();
  }

  private List<String> extractToolCodes(JsonNode invocationOptions) {
    if (invocationOptions == null || invocationOptions.isNull()) {
      return List.of();
    }
    JsonNode tooling = invocationOptions.path("tooling");
    JsonNode bindings = tooling.path("bindings");
    if (!bindings.isArray()) {
      return List.of();
    }
    return toStream(bindings).stream()
        .map(binding -> binding.path("toolCode").asText(null))
        .filter(StringUtils::hasText)
        .map(String::trim)
        .map(String::toLowerCase)
        .distinct()
        .collect(Collectors.toList());
  }

  private Instant parseInstant(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    if (!StringUtils.hasText(text)) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (Exception ex) {
      return null;
    }
  }

  private UUID resolveDefinitionId(PreviewDependenciesInput input) {
    if (!StringUtils.hasText(input.identifier())) {
      throw new IllegalArgumentException("Either definitionId or identifier must be provided");
    }
    ListAgentsInput listInput = new ListAgentsInput(input.identifier(), false, null);
    return listAgents(listInput).stream()
        .filter(summary -> summary.identifier().equalsIgnoreCase(input.identifier().trim()))
        .findFirst()
        .map(AgentSummary::id)
        .orElseThrow(
            () ->
                new AgentOpsClientException(
                    "Agent definition not found for identifier '%s'".formatted(input.identifier())));
  }

  private List<JsonNode> toStream(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) {
      return List.of();
    }
    return objectMapper.convertValue(arrayNode, LIST_NODE_TYPE);
  }

  private UUID valueAsUuid(JsonNode node) {
    String text = valueAsText(node);
    return StringUtils.hasText(text) ? UUID.fromString(text) : null;
  }

  private String valueAsText(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText(null);
  }
}
