package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.TestFlowBlueprintFactory;
import com.aiadvent.backend.flow.api.FlowValidationIssue;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintCompiler;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMetadata;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintSchemaVersion;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.blueprint.FlowStepTransitionsDraft;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FlowBlueprintValidatorTest {

  @Mock private AgentVersionRepository agentVersionRepository;

  private FlowBlueprintValidator validator;

  @BeforeEach
  void setUp() {
    FlowInteractionSchemaValidator schemaValidator = new FlowInteractionSchemaValidator();
    FlowDefinitionParser parser = new FlowDefinitionParser(schemaValidator);
    FlowBlueprintCompiler compiler = new FlowBlueprintCompiler(parser);
    validator = new FlowBlueprintValidator(compiler, agentVersionRepository);
    Mockito.lenient().when(agentVersionRepository.findAllById(Mockito.any())).thenReturn(List.of());
  }

  @Test
  void validateBlueprintReportsMissingAgentVersion() {
    UUID missingVersionId = UUID.randomUUID();
    FlowBlueprint blueprint = TestFlowBlueprintFactory.simpleBlueprint(missingVersionId);

    FlowBlueprintValidationResult result = validator.validateBlueprint(blueprint);

    assertThat(result.document()).isNotNull();
    assertThat(result.errors())
        .extracting(FlowValidationIssue::code)
        .contains("AGENT_VERSION_NOT_FOUND");
  }

  @Test
  void validateBlueprintReportsAgentVersionNotPublished() {
    UUID versionId = UUID.randomUUID();
    FlowBlueprint blueprint = TestFlowBlueprintFactory.simpleBlueprint(versionId);

    AgentDefinition definition = new AgentDefinition("draft-agent", "Draft Agent", null, true);
    setField(definition, "id", UUID.randomUUID());
    AgentVersion agentVersion =
        new AgentVersion(definition, 1, AgentVersionStatus.DRAFT, ChatProviderType.OPENAI, "openai", "gpt-4o-mini");
    setField(agentVersion, "id", versionId);

    Mockito.when(agentVersionRepository.findAllById(Mockito.any()))
        .thenReturn(List.of(agentVersion));

    FlowBlueprintValidationResult result = validator.validateBlueprint(blueprint);

    assertThat(result.errors())
        .extracting(FlowValidationIssue::code)
        .contains("AGENT_VERSION_NOT_PUBLISHED");
  }

  @Test
  void validateBlueprintReportsInactiveAgentDefinition() {
    UUID versionId = UUID.randomUUID();
    FlowBlueprint blueprint = TestFlowBlueprintFactory.simpleBlueprint(versionId);

    AgentDefinition definition = new AgentDefinition("inactive-agent", "Inactive Agent", null, false);
    setField(definition, "id", UUID.randomUUID());
    AgentVersion agentVersion =
        new AgentVersion(definition, 1, AgentVersionStatus.PUBLISHED, ChatProviderType.OPENAI, "openai", "gpt-4o-mini");
    setField(agentVersion, "id", versionId);

    Mockito.when(agentVersionRepository.findAllById(Mockito.any()))
        .thenReturn(List.of(agentVersion));

    FlowBlueprintValidationResult result = validator.validateBlueprint(blueprint);

    assertThat(result.errors())
        .extracting(FlowValidationIssue::code)
        .contains("AGENT_DEFINITION_INACTIVE");
  }

  @Test
  void validateBlueprintReturnsBlueprintErrorWhenTransitionsInvalid() {
    UUID agentVersionId = UUID.randomUUID();
    FlowBlueprint blueprint =
        new FlowBlueprint(
            FlowBlueprintSchemaVersion.CURRENT,
            new FlowBlueprintMetadata("Invalid flow", null, List.of()),
            null,
            null,
            "step-1",
            true,
            List.of(),
            FlowBlueprintMemory.empty(),
            List.of(
                new FlowBlueprintStep(
                    "step-1",
                    "Broken step",
                    agentVersionId.toString(),
                    "",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    new FlowStepTransitionsDraft(
                        new FlowStepTransitionsDraft.Success("missing-step", false),
                        null),
                    1)));

    FlowBlueprintValidationResult result = validator.validateBlueprint(blueprint);

    assertThat(result.document()).isNull();
    assertThat(result.errors())
        .extracting(FlowValidationIssue::code)
        .contains("BLUEPRINT_ERROR");
  }

  @Test
  void validateDefinitionOrThrowFailsFastWhenAgentVersionMissing() {
    UUID missingVersionId = UUID.randomUUID();
    FlowBlueprint blueprint = TestFlowBlueprintFactory.simpleBlueprint(missingVersionId);
    FlowDefinition definition =
        new FlowDefinition("missing-agent", 1, FlowDefinitionStatus.DRAFT, true, blueprint);

    assertThatThrownBy(() -> validator.validateDefinitionOrThrow(definition))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            throwable -> {
              ResponseStatusException exception = (ResponseStatusException) throwable;
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(exception.getReason()).contains("Agent version not found");
            });
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (NoSuchFieldException | IllegalAccessException exception) {
      throw new IllegalStateException("Failed to set field via reflection", exception);
    }
  }
}
