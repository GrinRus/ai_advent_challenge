package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.api.FlowDefinitionRequest;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMetadata;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintSchemaVersion;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowMemoryConfig;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.persistence.FlowDefinitionHistoryRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.telemetry.ConstructorTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FlowDefinitionServiceTest {

  @Mock private FlowDefinitionRepository flowDefinitionRepository;
  @Mock private FlowDefinitionHistoryRepository flowDefinitionHistoryRepository;
  @Mock private FlowBlueprintValidator flowBlueprintValidator;
  @Mock private ConstructorTelemetryService constructorTelemetryService;

  private ObjectMapper objectMapper;
  private FlowDefinitionService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
    service =
        new FlowDefinitionService(
            flowDefinitionRepository,
            flowDefinitionHistoryRepository,
            flowBlueprintValidator,
            objectMapper,
            constructorTelemetryService);
  }

  @Test
  void createDefinitionWithCurrentSchemaVersionSucceedsWithoutWarning() {
    FlowDefinitionRequest request = requestWithSchemaVersion(2, "actor");
    stubEmptyVersionHistory();
    stubValidationDocument();
    stubSaveDefinition();

    FlowDefinition result = service.createDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.getDefinition().schemaVersion()).isEqualTo(FlowBlueprintSchemaVersion.CURRENT);
    verify(constructorTelemetryService, never())
        .recordFlowBlueprintWarning(any(), any(), any(), any(), any());
  }

  @Test
  void createDefinitionWithLegacySchemaVersionLogsWarning() {
    FlowDefinitionRequest request = requestWithSchemaVersion(1, "actor");
    stubEmptyVersionHistory();
    stubValidationDocument();
    stubSaveDefinition();

    FlowDefinition result = service.createDefinition(request);

    verify(constructorTelemetryService)
        .recordFlowBlueprintWarning(
            eq("schema_version_legacy"), eq("create"), any(), eq("actor"), any());
    assertThat(result.getDefinition().schemaVersion()).isEqualTo(FlowBlueprintSchemaVersion.CURRENT);
  }

  @Test
  void createDefinitionWithUnsupportedSchemaVersionFails() {
    FlowDefinitionRequest request = requestWithSchemaVersion(3, "actor");
    stubEmptyVersionHistory();

    assertThatThrownBy(() -> service.createDefinition(request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            throwable ->
                assertThat(((ResponseStatusException) throwable).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

    verify(flowDefinitionRepository, never()).save(any());
    verify(constructorTelemetryService)
        .recordFlowBlueprintWarning(
            eq("schema_version_unsupported"), eq("create"), any(), eq("actor"), any());
    verify(flowBlueprintValidator, never()).validateBlueprintOrThrow(any());
  }

  private void stubEmptyVersionHistory() {
    when(flowDefinitionRepository.findByNameOrderByVersionDesc(anyString())).thenReturn(List.of());
  }

  private void stubValidationDocument() {
    UUID agentVersionId = UUID.randomUUID();
    FlowStepConfig config =
        new FlowStepConfig(
            "step-1",
            "Test",
            agentVersionId,
            "prompt",
            null,
            null,
            List.of(),
            List.of(),
            FlowStepTransitions.defaults(),
            1);
    when(flowBlueprintValidator.validateBlueprintOrThrow(any()))
        .thenReturn(new FlowDefinitionDocument("step-1", java.util.Map.of("step-1", config), FlowMemoryConfig.empty()));
  }

  private void stubSaveDefinition() {
    when(flowDefinitionRepository.save(any(FlowDefinition.class)))
        .thenAnswer(
            invocation -> {
              FlowDefinition definition = invocation.getArgument(0);
              assertThat(definition.getDefinition().schemaVersion())
                  .isEqualTo(FlowBlueprintSchemaVersion.CURRENT);
              setField(definition, "id", UUID.randomUUID());
              return definition;
            });
  }

  private FlowDefinitionRequest requestWithSchemaVersion(int schemaVersion, String actor) {
    FlowBlueprintStep step =
        new FlowBlueprintStep(
            "step-1",
            "Bootstrap",
            UUID.randomUUID().toString(),
            "prompt",
            null,
            null,
            List.of(),
            List.of(),
            null,
            1);
    FlowBlueprint blueprint =
        new FlowBlueprint(
            schemaVersion,
            FlowBlueprintMetadata.empty(),
            null,
            null,
            "step-1",
            true,
            List.of(),
            FlowBlueprintMemory.empty(),
            List.of(step));
    JsonNode node = objectMapper.valueToTree(blueprint);
    return new FlowDefinitionRequest("flow", "desc", actor, node, null, null);
  }

  private static void setField(Object target, String field, Object value) {
    try {
      java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException exception) {
      throw new RuntimeException(exception);
    }
  }
}
