package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SuggestedActionsSanitizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SuggestedActionsSanitizer sanitizer = new SuggestedActionsSanitizer(objectMapper);

  @Test
  void returnsNullWhenInputNull() {
    assertThat(sanitizer.sanitize(null)).isNull();
  }

  @Test
  void filtersOutNotAllowedLlmActions() {
    ObjectNode raw = objectMapper.createObjectNode();
    ArrayNode ruleBased = raw.putArray("ruleBased");
    ObjectNode approve = ruleBased.addObject();
    approve.put("id", "approve");
    approve.put("label", "Approve");

    ArrayNode allow = raw.putArray("allow");
    allow.add("approve");

    ArrayNode llm = raw.putArray("llm");
    ObjectNode discount = llm.addObject();
    discount.put("id", "discount");
    discount.put("label", "Offer discount");
    ObjectNode approveRecommendation = llm.addObject();
    approveRecommendation.put("id", "approve");
    approveRecommendation.put("label", "Approve quickly");

    JsonNode sanitized = sanitizer.sanitize(raw);
    assertThat(sanitized).isNotNull();

    ArrayNode ruleBasedNode = (ArrayNode) sanitized.get("ruleBased");
    assertThat(ruleBasedNode).isNotNull();
    assertThat(ruleBasedNode.size()).isEqualTo(1);

    ArrayNode llmNode = (ArrayNode) sanitized.get("llm");
    assertThat(llmNode).isNotNull();
    assertThat(llmNode.size()).isEqualTo(1);
    assertThat(llmNode.get(0).path("id").asText()).isEqualTo("approve");

    ArrayNode filteredNode = (ArrayNode) sanitized.get("filtered");
    assertThat(filteredNode).isNotNull();
    assertThat(filteredNode.size()).isEqualTo(1);
    assertThat(filteredNode.get(0).path("id").asText()).isEqualTo("discount");
  }
}

