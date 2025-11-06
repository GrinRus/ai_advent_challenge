package com.aiadvent.backend.flow.tool.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class QueryOverridingToolCallbackTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void mergesOverridesIntoNestedInputObject() throws Exception {
    AtomicReference<String> capturedPayload = new AtomicReference<>();
    ToolCallback delegate = recordingCallback(capturedPayload);

    ObjectNode overrides = mapper.createObjectNode();
    overrides.put("userNamespace", "telegram");
    overrides.put("userReference", "100");
    overrides.put("sourceChannel", "telegram");

    QueryOverridingToolCallback callback =
        new QueryOverridingToolCallback(delegate, mapper, overrides, null);

    ObjectNode payload = mapper.createObjectNode();
    ObjectNode input = mapper.createObjectNode();
    input.put("title", "Spring AI");
    input.put("content", "Overview");
    input.put("userNamespace", "default");
    input.put("userReference", "user123");
    input.put("sourceChannel", "assistant");
    payload.set("input", input);

    callback.call(mapper.writeValueAsString(payload));

    ObjectNode rewritten = (ObjectNode) mapper.readTree(capturedPayload.get());
    assertThat(rewritten.path("userNamespace").asText()).isEqualTo("telegram");
    assertThat(rewritten.path("userReference").asText()).isEqualTo("100");
    assertThat(rewritten.path("sourceChannel").asText()).isEqualTo("telegram");
    assertThat(rewritten.path("input").path("userNamespace").asText()).isEqualTo("telegram");
    assertThat(rewritten.path("input").path("userReference").asText()).isEqualTo("100");
    assertThat(rewritten.path("input").path("sourceChannel").asText()).isEqualTo("telegram");
    assertThat(rewritten.path("input").path("title").asText()).isEqualTo("Spring AI");
    assertThat(rewritten.path("input").path("content").asText()).isEqualTo("Overview");
  }

  private ToolCallback recordingCallback(AtomicReference<String> capturedPayload) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("notes.save_note")
            .description("Notes save tool")
            .inputSchema("{\"type\":\"object\"}")
            .build();

    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        capturedPayload.set(toolInput);
        return "{}";
      }
    };
  }
}
