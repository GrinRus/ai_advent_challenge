package com.aiadvent.mcp.backend.coding;

import com.aiadvent.mcp.backend.coding.CodingAssistantService.ApplyPatchPreviewRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.ApplyPatchPreviewResponse;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.GeneratePatchRequest;
import com.aiadvent.mcp.backend.coding.CodingAssistantService.GeneratePatchResponse;
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
          "Принимает workspaceId и инструкции, возвращает идентификатор патча. "
              + "Текущее поведение — заглушка без генерации diff (подлежит расширению).")
  GeneratePatchResponse generatePatch(GeneratePatchRequest request) {
    return codingAssistantService.generatePatch(request);
  }

  @Tool(
      name = "coding.review_patch",
      description =
          "Выполняет ревью ранее сгенерированного патча. Пока возвращает заглушечный ответ.")
  ReviewPatchResponse reviewPatch(ReviewPatchRequest request) {
    return codingAssistantService.reviewPatch(request);
  }

  @Tool(
      name = "coding.apply_patch_preview",
      description =
          "Подготавливает dry-run применения патча. Текущее поведение — заглушка без запуска проверок.")
  ApplyPatchPreviewResponse applyPatchPreview(ApplyPatchPreviewRequest request) {
    return codingAssistantService.applyPatchPreview(request);
  }
}
