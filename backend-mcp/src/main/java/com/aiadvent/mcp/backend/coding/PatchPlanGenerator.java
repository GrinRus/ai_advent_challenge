package com.aiadvent.mcp.backend.coding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fallback generator that creates a textual patch plan instead of touching real files. Keeps the
 * surrounding flow functional when the CLI backend is disabled or unavailable.
 */
@Component
class PatchPlanGenerator implements PatchGenerator {

  private static final String PATCH_PLAN_DIR = ".mcp";

  @Override
  public GenerationResult generate(GeneratePatchCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    if (!StringUtils.hasText(command.patchId())) {
      throw new IllegalArgumentException("patchId must not be blank");
    }
    if (!StringUtils.hasText(command.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    String planFile = PATCH_PLAN_DIR + "/patch-plan-" + command.patchId() + ".md";
    List<String> content = buildPlanContent(command, planFile);
    String diff = buildUnifiedDiff(planFile, content);

    PatchAnnotations annotations =
        new PatchAnnotations(
            List.of(planFile),
            List.of(
                "LLM генерация патча временно не подключена — создан план с инструкциями, требуется ручная доработка."),
            List.of());

    return new GenerationResult(
        "Создан план патча `" + planFile + "` с инструкциями и списком контекстных файлов.",
        diff,
        annotations,
        PatchUsage.empty(),
        true);
  }

  private List<String> buildPlanContent(GeneratePatchCommand command, String planFile) {
    List<String> lines = new ArrayList<>();
    lines.add("# MCP Patch Plan");
    lines.add("");
    lines.add("Patch ID: " + command.patchId());
    lines.add("Workspace ID: " + command.workspaceId());
    lines.add("Plan file: " + planFile);
    lines.add("");

    lines.add("## Instructions");
    lines.addAll(splitMultiline(command.instructions()));
    lines.add("");

    lines.add("## Target Paths");
    lines.addAll(splitOrPlaceholder(command.targetPaths(), "Target paths not specified."));
    lines.add("");

    lines.add("## Forbidden Paths");
    lines.addAll(splitOrPlaceholder(command.forbiddenPaths(), "Forbidden paths not specified."));
    lines.add("");

    lines.add("## Context Files");
    if (command.contextSnippets().isEmpty()) {
      lines.add("No additional context provided.");
    } else {
      for (ContextSnippet snippet : command.contextSnippets()) {
        String builder =
            "- "
                + snippet.path()
                + " (binary="
                + snippet.binary()
                + ", truncated="
                + snippet.truncated()
                + ")";
        lines.add(builder);
      }
    }
    lines.add("");

    Set<String> uniqueHints = new HashSet<>();
    if (!command.targetPaths().isEmpty()) {
      uniqueHints.add(
          "Фокус на путях: "
              + command.targetPaths().stream().collect(Collectors.joining(", ")));
    }
    if (!command.forbiddenPaths().isEmpty()) {
      uniqueHints.add(
          "Запрещённые пути: "
              + command.forbiddenPaths().stream().collect(Collectors.joining(", ")));
    }
    if (!command.contextSnippets().isEmpty()) {
      uniqueHints.add(
          "Контекстные файлы: "
              + command.contextSnippets().stream()
                  .map(ContextSnippet::path)
                  .collect(Collectors.joining(", ")));
    }

    lines.add("## Notes");
    if (uniqueHints.isEmpty()) {
      lines.add("- Нет дополнительных замечаний.");
    } else {
      uniqueHints.stream().sorted().forEach(hint -> lines.add("- " + hint));
    }

    return lines;
  }

  private List<String> splitMultiline(String value) {
    if (!StringUtils.hasText(value)) {
      return List.of("- (empty)");
    }
    return value.lines().map(line -> line.isEmpty() ? "-" : "- " + line).toList();
  }

  private List<String> splitOrPlaceholder(List<String> values, String placeholder) {
    if (values == null || values.isEmpty()) {
      return List.of("- " + placeholder);
    }
    return values.stream().map(item -> "- " + item).toList();
  }

  private String buildUnifiedDiff(String planFile, List<String> contentLines) {
    int lineCount = Math.max(1, contentLines.size());
    StringBuilder builder = new StringBuilder();
    builder
        .append("diff --git a/")
        .append(planFile)
        .append(" b/")
        .append(planFile)
        .append("\n");
    builder.append("new file mode 100644\n");
    builder.append("index 0000000..1111111\n");
    builder.append("--- /dev/null\n");
    builder.append("+++ b/").append(planFile).append("\n");
    builder.append("@@ -0,0 +1,").append(lineCount).append(" @@\n");
    for (String line : contentLines) {
      builder.append("+").append(line == null ? "" : line).append("\n");
    }
    return builder.toString();
  }
}
