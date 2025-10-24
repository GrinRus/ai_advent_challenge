package com.aiadvent.backend.flow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SuggestedActionsSanitizer {

  private static final Logger log = LoggerFactory.getLogger(SuggestedActionsSanitizer.class);

  private static final String SOURCE_RULE = "ruleBased";
  private static final String SOURCE_LLM = "llm";
  private static final String SOURCE_ANALYTICS = "analytics";

  private final ObjectMapper objectMapper;

  public SuggestedActionsSanitizer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public JsonNode sanitize(JsonNode raw) {
    if (raw == null || raw.isNull()) {
      return null;
    }

    SuggestedActionsAccumulator accumulator = new SuggestedActionsAccumulator();

    if (raw.isArray()) {
      parseArray(raw, accumulator);
    } else if (raw.isObject()) {
      parseObject(raw, accumulator);
    } else if (raw.isTextual()) {
      accumulator.addRuleBased(parseAction(raw, SOURCE_RULE, SOURCE_RULE));
    } else {
      log.debug("Unsupported suggestedActions payload type: {}", raw.getNodeType());
      return null;
    }

    accumulator.finalizeAllowList();
    accumulator.filterNonAllowed();

    return accumulator.buildResult(objectMapper);
  }

  private void parseArray(JsonNode array, SuggestedActionsAccumulator accumulator) {
    if (array == null || array.isNull()) {
      return;
    }
    if (array.isArray()) {
      for (JsonNode node : array) {
        routeBySource(parseAction(node, null, null), accumulator);
      }
    } else {
      routeBySource(parseAction(array, null, null), accumulator);
    }
  }

  private void parseObject(JsonNode object, SuggestedActionsAccumulator accumulator) {
    JsonNode allowNode =
        firstNonNull(object.get("allow"), object.get("allowList"), object.get("allowlist"));
    if (allowNode != null && allowNode.isArray()) {
      for (JsonNode node : allowNode) {
        parseAllowEntry(node, accumulator);
      }
    }

    parseArray(object.get("ruleBased"), SOURCE_RULE, SOURCE_RULE, accumulator::addRuleBased);
    parseArray(object.get("required"), SOURCE_RULE, SOURCE_RULE, accumulator::addRuleBased);

    parseArray(object.get("llm"), SOURCE_LLM, SOURCE_LLM, accumulator::addLlm);
    parseArray(object.get("llmCandidates"), SOURCE_LLM, SOURCE_LLM, accumulator::addLlm);
    parseArray(object.get("recommendations"), SOURCE_LLM, SOURCE_LLM, accumulator::addLlm);

    parseArray(object.get("analytics"), SOURCE_ANALYTICS, SOURCE_ANALYTICS, accumulator::addAnalytics);

    parseArray(object.get("items"), null, null, action -> routeBySource(action, accumulator));
    parseArray(object.get("actions"), null, null, action -> routeBySource(action, accumulator));
  }

  private void parseArray(
      JsonNode array,
      String defaultSource,
      String enforcedSource,
      java.util.function.Consumer<SuggestedAction> consumer) {
    if (array == null || array.isNull()) {
      return;
    }
    if (!array.isArray()) {
      SuggestedAction single = parseAction(array, defaultSource, enforcedSource);
      if (single != null) {
        consumer.accept(single);
      }
      return;
    }
    for (JsonNode node : array) {
      SuggestedAction action = parseAction(node, defaultSource, enforcedSource);
      if (action != null) {
        consumer.accept(action);
      }
    }
  }

  private void routeBySource(SuggestedAction action, SuggestedActionsAccumulator accumulator) {
    if (action == null) {
      return;
    }
    switch (action.source()) {
      case SOURCE_RULE -> accumulator.addRuleBased(action.withSource(SOURCE_RULE));
      case SOURCE_LLM -> accumulator.addLlm(action.withSource(SOURCE_LLM));
      case SOURCE_ANALYTICS -> accumulator.addAnalytics(action.withSource(SOURCE_ANALYTICS));
      default -> accumulator.addLlm(action.withSource(SOURCE_LLM));
    }
  }

  private void parseAllowEntry(JsonNode node, SuggestedActionsAccumulator accumulator) {
    if (node == null || node.isNull()) {
      return;
    }
    Optional<String> id = extractId(node);
    id.ifPresent(accumulator::addAllowedId);
    if (node.isObject()) {
      SuggestedAction action = parseAction(node, SOURCE_RULE, SOURCE_RULE);
      if (action != null) {
        accumulator.addRuleBased(action);
      }
    }
  }

  private SuggestedAction parseAction(JsonNode node, String defaultSource, String enforcedSource) {
    if (node == null || node.isNull()) {
      return null;
    }
    String id = null;
    String label = null;
    String source = defaultSource != null ? defaultSource : SOURCE_RULE;
    String description = null;
    String cta = null;
    JsonNode payload = null;

    if (node.isTextual()) {
      id = node.asText();
    } else if (node.isObject()) {
      ObjectNode objectNode = (ObjectNode) node;
      id = firstText(objectNode, "id", "action", "value");
      label = firstText(objectNode, "label", "title", "name");
      description = firstText(objectNode, "description", "details");
      cta = firstText(objectNode, "cta", "ctaLabel");
      JsonNode sourceNode = objectNode.get("source");
      if (sourceNode != null && sourceNode.isTextual()) {
        source = normalizeSource(sourceNode.asText(), defaultSource);
      }
      JsonNode payloadNode =
          firstNonNull(
              objectNode.get("payload"),
              objectNode.get("defaults"),
              objectNode.get("value"));
      if (payloadNode != null && !payloadNode.isNull()) {
        payload = payloadNode;
      }
    }

    if (id == null || id.isBlank()) {
      return null;
    }
    if (label == null || label.isBlank()) {
      label = humanize(id);
    }
    String finalSource = enforcedSource != null ? enforcedSource : source;
    return new SuggestedAction(id.trim(), label.trim(), finalSource, description, cta, payload);
  }

  private Optional<String> extractId(JsonNode node) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (node.isTextual()) {
      String value = node.asText();
      return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
    if (node.isObject()) {
      String text = firstText(node, "id", "action", "value");
      return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
    return Optional.empty();
  }

  private String normalizeSource(String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String normalized = raw.trim();
    if (normalized.equalsIgnoreCase("rule") || normalized.equalsIgnoreCase("rule_based")) {
      return SOURCE_RULE;
    }
    if (normalized.equalsIgnoreCase("ai") || normalized.equalsIgnoreCase("llm")) {
      return SOURCE_LLM;
    }
    if (normalized.equalsIgnoreCase("analytics") || normalized.equalsIgnoreCase("data")) {
      return SOURCE_ANALYTICS;
    }
    return fallback;
  }

  private String humanize(String id) {
    String replaced = id.replace('_', ' ').replace('-', ' ').trim();
    if (replaced.isEmpty()) {
      return id;
    }
    return replaced.substring(0, 1).toUpperCase(Locale.ROOT) + replaced.substring(1);
  }

  private String firstText(JsonNode node, String... paths) {
    for (String path : paths) {
      JsonNode value = node.get(path);
      if (value != null && value.isTextual() && !value.asText().isBlank()) {
        return value.asText();
      }
    }
    return null;
  }

  private JsonNode firstNonNull(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && !node.isNull()) {
        return node;
      }
    }
    return null;
  }

  private record SuggestedAction(
      String id, String label, String source, String description, String ctaLabel, JsonNode payload) {

    SuggestedAction withSource(String replacement) {
      if (replacement == null || replacement.equals(source)) {
        return this;
      }
      return new SuggestedAction(id, label, replacement, description, ctaLabel, payload);
    }

    ObjectNode toJson(ObjectMapper mapper) {
      ObjectNode node = mapper.createObjectNode();
      node.put("id", id);
      node.put("label", label);
      node.put("source", source);
      if (description != null && !description.isBlank()) {
        node.put("description", description);
      }
      if (ctaLabel != null && !ctaLabel.isBlank()) {
        node.put("ctaLabel", ctaLabel);
      }
      if (payload != null && !payload.isNull()) {
        node.set("payload", payload.deepCopy());
      }
      return node;
    }
  }

  private static final class FilteredAction {
    private final String id;
    private final String source;
    private final String reason;

    private FilteredAction(String id, String source, String reason) {
      this.id = id;
      this.source = source;
      this.reason = reason;
    }

    ObjectNode toJson(ObjectMapper mapper) {
      ObjectNode node = mapper.createObjectNode();
      if (id != null) {
        node.put("id", id);
      }
      if (source != null) {
        node.put("source", source);
      }
      node.put("reason", reason);
      return node;
    }
  }

  private final class SuggestedActionsAccumulator {

    private final Map<String, SuggestedAction> ruleBased = new LinkedHashMap<>();
    private final Map<String, SuggestedAction> llm = new LinkedHashMap<>();
    private final Map<String, SuggestedAction> analytics = new LinkedHashMap<>();
    private final List<FilteredAction> filtered = new ArrayList<>();
    private final Set<String> allowIds = new LinkedHashSet<>();

    void addRuleBased(SuggestedAction action) {
      if (action == null) {
        return;
      }
      ruleBased.putIfAbsent(action.id(), action.withSource(SOURCE_RULE));
      allowIds.add(action.id());
    }

    void addLlm(SuggestedAction action) {
      if (action == null) {
        return;
      }
      llm.putIfAbsent(action.id(), action.withSource(SOURCE_LLM));
    }

    void addAnalytics(SuggestedAction action) {
      if (action == null) {
        return;
      }
      analytics.putIfAbsent(action.id(), action.withSource(SOURCE_ANALYTICS));
    }

    void addAllowedId(String id) {
      if (id != null && !id.isBlank()) {
        allowIds.add(id.trim());
      }
    }

    void finalizeAllowList() {
      if (allowIds.isEmpty()) {
        allowIds.addAll(ruleBased.keySet());
      } else {
        allowIds.addAll(ruleBased.keySet());
      }
    }

    void filterNonAllowed() {
      if (allowIds.isEmpty()) {
        if (!llm.isEmpty() || !analytics.isEmpty()) {
          log.warn("Dropping llm/analytics suggested actions because allow list is empty");
        }
        llm.clear();
        analytics.clear();
        return;
      }
      filterMap(llm);
      filterMap(analytics);
    }

    private void filterMap(Map<String, SuggestedAction> actions) {
      List<String> toRemove = new ArrayList<>();
      for (SuggestedAction action : actions.values()) {
        if (!allowIds.contains(action.id())) {
          filtered.add(new FilteredAction(action.id(), action.source(), "not-allowed"));
          log.debug(
              "Dropping suggested action '{}' from source '{}' because it is not present in allow list",
              action.id(),
              action.source());
          toRemove.add(action.id());
        }
      }
      toRemove.forEach(actions::remove);
    }

    JsonNode buildResult(ObjectMapper mapper) {
      ObjectNode result = mapper.createObjectNode();
      result.set("ruleBased", toArray(mapper, ruleBased.values()));
      if (!llm.isEmpty()) {
        result.set("llm", toArray(mapper, llm.values()));
      }
      if (!analytics.isEmpty()) {
        result.set("analytics", toArray(mapper, analytics.values()));
      }
      if (!allowIds.isEmpty()) {
        ArrayNode allowNode = mapper.createArrayNode();
        allowIds.forEach(allowNode::add);
        result.set("allow", allowNode);
      }
      if (!filtered.isEmpty()) {
        ArrayNode filteredNode = mapper.createArrayNode();
        filtered.forEach(item -> filteredNode.add(item.toJson(mapper)));
        result.set("filtered", filteredNode);
      }
      return result;
    }

    private ArrayNode toArray(ObjectMapper mapper, Iterable<SuggestedAction> actions) {
      ArrayNode arrayNode = mapper.createArrayNode();
      for (SuggestedAction action : actions) {
        arrayNode.add(action.toJson(mapper));
      }
      return arrayNode;
    }
  }
}
