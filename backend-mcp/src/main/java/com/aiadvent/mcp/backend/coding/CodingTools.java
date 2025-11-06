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
          "Создаёт черновой diff по инструкциям оператора и регистрирует патч. Ожидает JSON-объект вида\n"
              + "{\"workspaceId\": \"...\", \"instructions\": \"...\", \"targetPaths\": [\"src/App.java\"], "
              + "\"forbiddenPaths\": [...], \"contextFiles\": [{\"path\": \"README.md\", \"maxBytes\": 20480}]}. "
              + "Поля workspaceId и instructions обязательны, инструкции должны быть непустыми. Пути указываются "
              + "относительно корня workspace и не могут ссылаться на каталоги (contextFiles читает только файлы). "
              + "targetPaths помогает сфокусировать генерацию, forbiddenPaths блокирует затрагивание файлов. Чтобы "
              + "создать новый файл, добавьте путь в targetPaths и опишите содержимое во instructions - diff будет "
              + "включать блок с new file. Пример запроса: {\"workspaceId\": \"notes-1\", \"instructions\": \"добавь README\", "
              + "\"targetPaths\": [\"docs/README.md\"], \"contextFiles\": [{\"path\": \"docs/index.md\"}]}.")
  GeneratePatchResponse generatePatch(GeneratePatchRequest request) {
    return codingAssistantService.generatePatch(request);
  }

  @Tool(
      name = "coding.review_patch",
      description =
          "Выполняет автоматическое ревью ранее сгенерированного патча. Принимает {\"workspaceId\": \"...\", "
              + "\"patchId\": \"...\", \"focus\": [\"risks\",\"tests\"]}. Обязательны workspaceId и patchId. focus - "
              + "необязательный список режимов анализа (risks, tests, migration); регистр нечувствителен. В ответе "
              + "возвращаются findings, советы по тестам, nextSteps и аннотации, которые можно показывать оператору "
              + "перед публикацией. Пример запроса: {\"workspaceId\": \"notes-1\", \"patchId\": \"abc123\", \"focus\": [\"risks\"]}.")
  ReviewPatchResponse reviewPatch(ReviewPatchRequest request) {
    return codingAssistantService.reviewPatch(request);
  }

  @Tool(
      name = "coding.apply_patch_preview",
      description =
          "Применяет патч в preview-режиме: git apply --check, git apply, git diff и опциональный запуск gradle-команд в "
              + "Docker. Тело запроса {\"workspaceId\": \"...\", \"patchId\": \"...\", \"commands\": [\"./gradlew test\"], "
              + "\"dryRun\": true, \"timeout\": \"PT15M\"}. workspaceId и patchId обязательны. По умолчанию dryRun=true, "
              + "timeout подразумевает ISO 8601 duration. Команды поддерживают только gradle/./gradlew. После успешного "
              + "прогона diff откатывается, а метаданные dry-run обновляются в реестре. Пример запроса: {\"workspaceId\": "
              + "\"notes-1\", \"patchId\": \"abc123\", \"commands\": [\"./gradlew test\"], \"dryRun\": true}.")
  ApplyPatchPreviewResponse applyPatchPreview(ApplyPatchPreviewRequest request) {
    return codingAssistantService.applyPatchPreview(request);
  }

  @Tool(
      name = "coding.list_patches",
      description =
          "Возвращает список патчей, связанных с workspace: {\"workspaceId\": \"...\"}. Поле обязательно. Ответ содержит "
              + "patchId, статус, requiresManualReview, наличие dry-run и прочие метрики, что удобно для отображения "
              + "очереди задач оператора или повторного запуска review/apply. Пример запроса: {\"workspaceId\": \"notes-1\"}.")
  ListPatchesResponse listPatches(ListPatchesRequest request) {
    return codingAssistantService.listPatches(request);
  }

  @Tool(
      name = "coding.discard_patch",
      description =
          "Удаляет патч из реестра и освобождает связанные ресурсы. Ожидает {\"workspaceId\": \"...\", "
              + "\"patchId\": \"...\"} - оба поля обязательны. Используйте после успешной публикации или если патч "
              + "более не нужен, чтобы очистить реестр и прекратить дальнейшие dry-run/review для него. Пример запроса: {\"workspaceId\": "
              + "\"notes-1\", \"patchId\": \"abc123\"}.")
  DiscardPatchResponse discardPatch(DiscardPatchRequest request) {
    return codingAssistantService.discardPatch(request);
  }
}
