package com.aiadvent.mcp.backend.coding;

import com.aiadvent.mcp.backend.coding.CodingAssistantService.ApplyPatchPreviewRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.ApplyPatchPreviewResponse;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.DiscardPatchRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.DiscardPatchResponse;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.GeneratePatchRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.GeneratePatchResponse;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.ListPatchesRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.ListPatchesResponse;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.ReviewPatchRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.ReviewPatchResponse;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
class CodingTools {

  private final CodingAssistantService codingAssistantService;

  CodingTools(CodingAssistantService codingAssistantService) {
    this.codingAssistantService =
        Objects.requireNonNull(codingAssistantService, "codingAssistantService");
  }

  @Tool(
      name = "coding.generate_patch",
      description =
          "Генерирует черновой diff на основе инструкций и сохраняет патч в реестре.")
  GeneratePatchResponse generatePatch(GeneratePatchRequest request) {
    return codingAssistantService.generatePatch(request);
  }

  @Tool(
      name = "coding.review_patch",
      description =
          "Анализирует ранее созданный патч, возвращает риски, рекомендации по тестам и следующие шаги.")
  ReviewPatchResponse reviewPatch(ReviewPatchRequest request) {
    return codingAssistantService.reviewPatch(request);
  }

  @Tool(
      name = "coding.apply_patch_preview",
      description =
          "Выполняет dry-run применения патча (git apply, diff, Docker команды) с ручным подтверждением.")
  ApplyPatchPreviewResponse applyPatchPreview(ApplyPatchPreviewRequest request) {
    return codingAssistantService.applyPatchPreview(request);
  }

  @Tool(
      name = "coding.list_patches",
      description =
          "Возвращает список активных патчей для workspace с метаданными и статусом dry-run.")
  ListPatchesResponse listPatches(ListPatchesRequest request) {
    return codingAssistantService.listPatches(request);
  }

  @Tool(
      name = "coding.discard_patch",
      description =
          "Удаляет патч из реестра и освобождает ресурсы. Требует явного указания patchId.")
  DiscardPatchResponse discardPatch(DiscardPatchRequest request) {
    return codingAssistantService.discardPatch(request);
  }
}
