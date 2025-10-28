package com.aiadvent.backend.mcp.flowops;

import com.aiadvent.backend.mcp.flowops.FlowOpsClient.DiffEntry;
import com.aiadvent.backend.mcp.flowops.FlowOpsClient.FlowDiffResult;
import com.aiadvent.backend.mcp.flowops.FlowOpsClient.FlowPublishResult;
import com.aiadvent.backend.mcp.flowops.FlowOpsClient.FlowSummary;
import com.aiadvent.backend.mcp.flowops.FlowOpsClient.Issue;
import com.aiadvent.backend.mcp.flowops.FlowOpsClient.ListFlowsInput;
import com.aiadvent.backend.mcp.flowops.FlowOpsClient.ValidateBlueprintInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class FlowOpsTools {

  private final FlowOpsClient client;

  public FlowOpsTools(FlowOpsClient client) {
    this.client = client;
  }

  @Tool(
      name = "flow_ops.list_flows",
      description =
          "Возвращает список flow definitions. Поддерживает фильтрацию по имени и статусу.")
  public ListFlowsResult listFlows(ListFlowsInputDto input) {
    ListFlowsInput parsed =
        new ListFlowsInput(
            input != null ? input.query() : null,
            input != null ? input.status() : null,
            input != null ? input.limit() : null);
    List<FlowSummary> summaries = client.listFlows(parsed);
    return new ListFlowsResult(summaries);
  }

  @Tool(
      name = "flow_ops.diff_flow_version",
      description =
          "Сравнивает две версии flow definition и возвращает отличие по JSON-путям.")
  public FlowDiffResult diffFlowVersion(DiffFlowInput input) {
    if (input == null || input.definitionId() == null) {
      throw new IllegalArgumentException("definitionId is required");
    }
    return client.diffDefinition(
        new FlowOpsClient.DiffFlowVersionInput(
            input.definitionId(), input.baseVersion(), input.compareVersion()));
  }

  @Tool(
      name = "flow_ops.validate_blueprint",
      description =
          "Прогоняет валидатор flow blueprint и возвращает список ошибок и предупреждений. "
              + "Опционально можно указать stepId для узконаправленной проверки.")
  public ValidationResult validateBlueprint(ValidateBlueprintInputDto input) {
    if (input == null || input.blueprint() == null) {
      throw new IllegalArgumentException("Field 'blueprint' is required");
    }
    return toValidationResult(
        client.validateBlueprint(new ValidateBlueprintInput(input.blueprint(), input.stepId())));
  }

  @Tool(
      name = "flow_ops.publish_flow",
      description =
          "Публикует flow definition. Требуется ID, пользователь и опциональные changeNotes.")
  public FlowPublishResult publishFlow(PublishFlowInputDto input) {
    if (input == null || input.definitionId() == null) {
      throw new IllegalArgumentException("definitionId is required");
    }
    return client.publishDefinition(
        new FlowOpsClient.PublishFlowInput(
            input.definitionId(), input.updatedBy(), input.changeNotes()));
  }

  @Tool(
      name = "flow_ops.rollback_flow",
      description =
          "Создаёт новую версию flow, копируя blueprint из указанной версии истории, и публикует её.")
  public FlowPublishResult rollbackFlow(RollbackFlowInputDto input) {
    if (input == null || input.definitionId() == null) {
      throw new IllegalArgumentException("definitionId is required");
    }
    return client.rollbackDefinition(
        new FlowOpsClient.RollbackFlowInput(
            input.definitionId(), input.targetVersion(), input.updatedBy(), input.changeNotes()));
  }

  private ValidationResult toValidationResult(FlowOpsClient.ValidationResult result) {
    return new ValidationResult(result.errors(), result.warnings());
  }

  public record ListFlowsInputDto(String query, String status, Integer limit) {}

  public record ListFlowsResult(List<FlowSummary> flows) {}

  public record DiffFlowInput(UUID definitionId, int baseVersion, int compareVersion) {}

  public record ValidateBlueprintInputDto(JsonNode blueprint, String stepId) {}

  public record ValidationResult(List<Issue> errors, List<Issue> warnings) {}

  public record PublishFlowInputDto(UUID definitionId, String updatedBy, String changeNotes) {}

  public record RollbackFlowInputDto(
      UUID definitionId, int targetVersion, String updatedBy, String changeNotes) {}
}

