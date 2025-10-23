package com.aiadvent.backend.flow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlowDefinitionParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FlowDefinitionParser parser = new FlowDefinitionParser();

  @Test
  void appliesDefaultSharedMemoryConfiguration() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Bootstrap");
    step.put("agentVersionId", UUID.randomUUID().toString());
    step.put("prompt", "Kick things off");

    FlowDefinition definition =
        new FlowDefinition("default-memory", 1, FlowDefinitionStatus.PUBLISHED, true, root);

    FlowDefinitionDocument document = parser.parse(definition);
    FlowStepConfig config = document.step("step-1");

    assertThat(config.memoryWrites())
        .extracting(MemoryWriteConfig::channel)
        .contains("shared");
    assertThat(config.memoryReads())
        .extracting(MemoryReadConfig::channel)
        .contains("shared");
  }
}
