package com.aiadvent.mcp.backend.flowops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Component
class FlowOpsClient {

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  FlowOpsClient(WebClient flowOpsWebClient, ObjectMapper objectMapper) {
    this.webClient = flowOpsWebClient;
    this.objectMapper = objectMapper;
  }

  List<FlowSummary> listFlows(ListFlowsInput input) {
    JsonNode node =
        webClient
            .get()
            .uri("/api/flows/definitions")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    if (node == null || !node.isArray()) {
      return List.of();
    }

    String query = normalize(input.query());
    String statusFilter = normalize(input.status());
    List<FlowSummary> flows = new ArrayList<>();

    for (JsonNode item : node) {
      FlowSummary summary = toFlowSummary(item);
      if (summary == null) {
        continue;
      }
      if (StringUtils.hasText(query)
          && !summary.name().toLowerCase().contains(query)) {
        continue;
      }
      if (StringUtils.hasText(statusFilter)
          && (summary.status() == null
              || !summary.status().toLowerCase().contains(statusFilter))) {
        continue;
      }
      flows.add(summary);
    }

    flows.sort(Comparator.comparing(FlowSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    if (input.limit() != null && input.limit() > 0 && flows.size() > input.limit()) {
      return flows.subList(0, input.limit());
    }
    return flows;
  }

  FlowDiffResult diffDefinition(DiffFlowVersionInput input) {
    JsonNode historyNode =
        webClient
            .get()
            .uri("/api/flows/definitions/{id}/history", input.definitionId())
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();
    if (historyNode == null || !historyNode.isArray()) {
      throw new IllegalStateException("Flow history response is empty");
    }

    JsonNode baseNode = null;
    JsonNode compareNode = null;
    for (JsonNode entry : historyNode) {
      int version = entry.path("version").asInt(-1);
      if (version == input.baseVersion()) {
        baseNode = entry;
      } else if (version == input.compareVersion()) {
        compareNode = entry;
      }
    }
    if (baseNode == null || compareNode == null) {
      throw new IllegalArgumentException("Requested versions not found in history");
    }

    JsonNode baseBlueprint = baseNode.path("definition");
    JsonNode compareBlueprint = compareNode.path("definition");

    List<DiffEntry> differences =
        calculateDiff(baseBlueprint, compareBlueprint, "");

    return new FlowDiffResult(
        input.definitionId(),
        input.baseVersion(),
        input.compareVersion(),
        differences,
        pretty(baseBlueprint),
        pretty(compareBlueprint));
  }

  ValidationResult validateBlueprint(ValidateBlueprintInput input) {
    if (input.blueprint() == null) {
      throw new IllegalArgumentException("Blueprint payload must not be null");
    }
    ObjectNode payload = objectMapper.createObjectNode();
    payload.set("blueprint", input.blueprint());
    if (StringUtils.hasText(input.stepId())) {
      payload.put("stepId", input.stepId());
    }

    JsonNode response =
        webClient
            .post()
            .uri("/api/flows/definitions/validation/step")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    List<Issue> errors = readIssues(response, "errors");
    List<Issue> warnings = readIssues(response, "warnings");
    return new ValidationResult(errors, warnings);
  }

  FlowPublishResult publishDefinition(PublishFlowInput input) {
    ObjectNode payload = objectMapper.createObjectNode();
    if (StringUtils.hasText(input.updatedBy())) {
      payload.put("updatedBy", input.updatedBy().trim());
    }
    if (StringUtils.hasText(input.changeNotes())) {
      payload.put("changeNotes", input.changeNotes().trim());
    }
    JsonNode response =
        webClient
            .post()
            .uri("/api/flows/definitions/{id}/publish", input.definitionId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();
    return toPublishResult(response);
  }

  FlowPublishResult rollbackDefinition(RollbackFlowInput input) {
    JsonNode definitionNode =
        webClient
            .get()
            .uri("/api/flows/definitions/{id}", input.definitionId())
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();
    if (definitionNode == null || definitionNode.isNull()) {
      throw new IllegalArgumentException("Flow definition not found: " + input.definitionId());
    }

    JsonNode historyNode =
        webClient
            .get()
            .uri("/api/flows/definitions/{id}/history", input.definitionId())
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();
    if (historyNode == null || !historyNode.isArray()) {
      throw new IllegalStateException("Flow history is empty: " + input.definitionId());
    }

    JsonNode targetHistory =
        findHistoryEntry(historyNode, input.targetVersion())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Version "
                            + input.targetVersion()
                            + " not found for definition "
                            + input.definitionId()));

    JsonNode targetDefinition = targetHistory.path("definition");
    if (targetDefinition == null || targetDefinition.isNull()) {
      throw new IllegalStateException("History entry does not contain blueprint definition");
    }

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("name", definitionNode.path("name").asText(null));
    payload.put("description", definitionNode.path("description").asText(null));
    if (StringUtils.hasText(input.updatedBy())) {
      payload.put("updatedBy", input.updatedBy().trim());
    }
    payload.set("definition", targetDefinition);

    String changeNotes =
        StringUtils.hasText(input.changeNotes())
            ? input.changeNotes().trim()
            : "Rollback to version " + input.targetVersion();
    payload.put("changeNotes", changeNotes);

    JsonNode updated =
        webClient
            .post()
            .uri("/api/flows/definitions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    UUID newDefinitionId = UUID.fromString(updated.path("id").asText());
    PublishFlowInput publishInput =
        new PublishFlowInput(newDefinitionId, input.updatedBy(), changeNotes);
    return publishDefinition(publishInput);
  }

  private Optional<JsonNode> findHistoryEntry(JsonNode historyNode, int version) {
    for (JsonNode entry : historyNode) {
      if (entry.path("version").asInt(-1) == version) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  private FlowPublishResult toPublishResult(JsonNode node) {
    if (node == null || node.isNull()) {
      throw new IllegalStateException("Publish response is empty");
    }
    UUID id = UUID.fromString(node.path("id").asText());
    int version = node.path("version").asInt();
    String status = node.path("status").asText(null);
    boolean active = node.path("active").asBoolean(false);
    Instant publishedAt = parseInstant(node.path("publishedAt").asText(null));

    return new FlowPublishResult(id, version, status, active, publishedAt);
  }

  private List<Issue> readIssues(JsonNode response, String fieldName) {
    if (response == null || response.isNull()) {
      return List.of();
    }
    JsonNode arrayNode = response.path(fieldName);
    if (!arrayNode.isArray()) {
      return List.of();
    }
    List<Issue> issues = new ArrayList<>();
    for (JsonNode item : arrayNode) {
      issues.add(
          new Issue(
              item.path("code").asText(null),
              item.path("message").asText(null),
              item.path("pointer").asText(null),
              item.path("stepId").asText(null)));
    }
    return issues;
  }

  private FlowSummary toFlowSummary(JsonNode node) {
    try {
      UUID id = UUID.fromString(node.path("id").asText());
      String name = node.path("name").asText(null);
      int version = node.path("version").asInt(-1);
      String status = node.path("status").asText(null);
      boolean active = node.path("active").asBoolean(false);
      String description = node.path("description").asText(null);
      String updatedBy = node.path("updatedBy").asText(null);
      Instant updatedAt = parseInstant(node.path("updatedAt").asText(null));
      Instant publishedAt = parseInstant(node.path("publishedAt").asText(null));
      return new FlowSummary(
          id, name, version, status, active, description, updatedBy, updatedAt, publishedAt);
    } catch (Exception ex) {
      return null;
    }
  }

  private Instant parseInstant(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (Exception ex) {
      return null;
    }
  }

  private String pretty(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (JsonProcessingException e) {
      return node.toString();
    }
  }

  private List<DiffEntry> calculateDiff(JsonNode baseNode, JsonNode compareNode, String pointer) {
    List<DiffEntry> diffs = new ArrayList<>();
    if (baseNode == null && compareNode == null) {
      return diffs;
    }
    if (baseNode == null || compareNode == null) {
      diffs.add(
          new DiffEntry(
              pointer,
              pretty(baseNode),
              pretty(compareNode)));
      return diffs;
    }
    if (baseNode.equals(compareNode)) {
      return diffs;
    }
    if (baseNode.isObject() && compareNode.isObject()) {
      Set<String> fields = new HashSet<>();
      baseNode.fieldNames().forEachRemaining(fields::add);
      compareNode.fieldNames().forEachRemaining(fields::add);
      for (String field : fields) {
        String childPointer = pointer + "/" + field;
        diffs.addAll(
            calculateDiff(baseNode.get(field), compareNode.get(field), childPointer));
      }
    } else if (baseNode.isArray() && compareNode.isArray()) {
      int max = Math.max(baseNode.size(), compareNode.size());
      for (int i = 0; i < max; i++) {
        String childPointer = pointer + "/" + i;
        JsonNode left = i < baseNode.size() ? baseNode.get(i) : null;
        JsonNode right = i < compareNode.size() ? compareNode.get(i) : null;
        diffs.addAll(calculateDiff(left, right, childPointer));
      }
    } else {
      diffs.add(new DiffEntry(pointer, pretty(baseNode), pretty(compareNode)));
    }
    return diffs;
  }

  private String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase() : null;
  }

  record FlowSummary(
      UUID id,
      String name,
      int version,
      String status,
      boolean active,
      String description,
      String updatedBy,
      Instant updatedAt,
      Instant publishedAt) {}

  record DiffEntry(String pointer, String baseValue, String compareValue) {}

  record FlowDiffResult(
      UUID definitionId,
      int baseVersion,
      int compareVersion,
      List<DiffEntry> differences,
      String baseDefinition,
      String compareDefinition) {}

  record ListFlowsInput(String query, String status, Integer limit) {}

  record DiffFlowVersionInput(UUID definitionId, int baseVersion, int compareVersion) {}

  record ValidateBlueprintInput(JsonNode blueprint, String stepId) {}

  record Issue(String code, String message, String pointer, String stepId) {}

  record ValidationResult(List<Issue> errors, List<Issue> warnings) {}

  record PublishFlowInput(UUID definitionId, String updatedBy, String changeNotes) {}

  record FlowPublishResult(UUID definitionId, int version, String status, boolean active, Instant publishedAt) {}

  record RollbackFlowInput(UUID definitionId, int targetVersion, String updatedBy, String changeNotes) {}
}

