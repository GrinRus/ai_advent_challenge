package com.aiadvent.backend.flow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.aiadvent.backend.flow.memory.FlowMemoryChannels.CONVERSATION;
import static com.aiadvent.backend.flow.memory.FlowMemoryChannels.SHARED;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.validation.FlowBlueprintIssueCodes;
import com.aiadvent.backend.flow.validation.FlowBlueprintParsingException;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlowDefinitionParserTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper().configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
  private final FlowDefinitionParser parser =
      new FlowDefinitionParser(new FlowInteractionSchemaValidator());

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

    FlowDefinition definition = createDefinition("default-memory", root);

    FlowDefinitionDocument document = parser.parse(definition);
    FlowStepConfig config = document.step("step-1");

    assertThat(config.memoryWrites())
        .extracting(MemoryWriteConfig::channel)
        .contains(SHARED);
    assertThat(config.memoryReads())
        .extracting(MemoryReadConfig::channel)
        .contains(SHARED, CONVERSATION);

    FlowMemoryConfig memoryConfig = document.memoryConfig();
    assertThat(memoryConfig.sharedChannels())
        .extracting(FlowMemoryChannelConfig::channel)
        .contains(CONVERSATION, SHARED);
  }

  @Test
  void parsesMemoryRetentionPolicy() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Bootstrap");
    step.put("agentVersionId", UUID.randomUUID().toString());
    ObjectNode memory = root.putObject("memory");
    ArrayNode shared = memory.putArray("sharedChannels");
    ObjectNode sharedChannel = shared.addObject();
    sharedChannel.put("id", "shared");
    sharedChannel.put("retentionVersions", 3);
    sharedChannel.put("retentionDays", 5);
    ObjectNode analyticsChannel = shared.addObject();
    analyticsChannel.put("id", "analytics");
    analyticsChannel.put("retentionVersions", 2);
    analyticsChannel.put("retentionDays", 1);

    FlowDefinition definition = createDefinition("memory-policy", root);
    FlowDefinitionDocument document = parser.parse(definition);

    FlowMemoryConfig memoryConfig = document.memoryConfig();
    assertThat(memoryConfig.sharedChannels())
        .extracting(FlowMemoryChannelConfig::channel)
        .containsExactly("conversation", "shared", "analytics");
    FlowMemoryChannelConfig sharedConfig = memoryConfig.sharedChannels().get(1);
    assertThat(sharedConfig.retentionVersions()).isEqualTo(3);
    assertThat(sharedConfig.retentionTtl()).isEqualTo(java.time.Duration.ofDays(5));
  }

  @Test
  void parsesInteractionConfiguration() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Collect context");
    step.put("agentVersionId", UUID.randomUUID().toString());
    ObjectNode interaction = step.putObject("interaction");
    interaction.put("type", "input_form");
    interaction.put("title", "Need details");
    interaction.put("description", "Provide missing inputs");
    interaction.put("dueInMinutes", 15);
    interaction.putObject("payloadSchema").put("type", "object");

    FlowDefinition definition = createDefinition("interaction-flow", root);

    FlowDefinitionDocument document = parser.parse(definition);
    FlowStepConfig config = document.step("step-1");

    assertThat(config.interaction()).isNotNull();
    assertThat(config.interaction().type())
        .isEqualTo(com.aiadvent.backend.flow.domain.FlowInteractionType.INPUT_FORM);
    assertThat(config.interaction().title()).isEqualTo("Need details");
    assertThat(config.interaction().description()).isEqualTo("Provide missing inputs");
    assertThat(config.interaction().dueInMinutes()).isEqualTo(15);
    assertThat(config.interaction().payloadSchema()).isNotNull();
  }

  @Test
  void rejectsUnsupportedFormat() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Collect context");
    step.put("agentVersionId", UUID.randomUUID().toString());
    ObjectNode interaction = step.putObject("interaction");
    interaction.putObject("payloadSchema").put("type", "string").put("format", "unsupported");

    FlowDefinition definition = createDefinition("invalid-format", root);

    assertThatThrownBy(() -> parser.parse(definition))
        .isInstanceOf(FlowBlueprintParsingException.class)
        .satisfies(
            throwable ->
                assertThat(((FlowBlueprintParsingException) throwable).issues())
                    .singleElement()
                    .satisfies(issue -> {
                      assertThat(issue.code()).isEqualTo(FlowBlueprintIssueCodes.INTERACTION_SCHEMA_INVALID);
                      assertThat(issue.path()).isEqualTo("/steps/step-1/interaction/payloadSchema");
                    }));
  }

  private FlowDefinition createDefinition(String name, ObjectNode root) {
    try {
      FlowBlueprint blueprint = objectMapper.treeToValue(root, FlowBlueprint.class);
      return new FlowDefinition(name, 1, FlowDefinitionStatus.PUBLISHED, true, blueprint);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to build blueprint for test", exception);
    }
  }
}
