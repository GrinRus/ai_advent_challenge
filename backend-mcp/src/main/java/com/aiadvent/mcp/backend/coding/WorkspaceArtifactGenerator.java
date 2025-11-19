package com.aiadvent.mcp.backend.coding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WorkspaceArtifactGenerator {

  private final CodingAssistantProperties properties;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

  WorkspaceArtifactGenerator(
      CodingAssistantProperties properties,
      ObjectMapper objectMapper,
      @Qualifier("codingArtifactChatClientBuilder")
          ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.chatClientBuilderProvider =
        Objects.requireNonNull(chatClientBuilderProvider, "chatClientBuilderProvider");
  }

  GenerationResult generate(Command command) {
    Objects.requireNonNull(command, "command");
    CodingAssistantProperties.OpenAiProperties openai = requireOpenAi();
    ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
    if (builder == null) {
      throw new IllegalStateException("Coding OpenAI chat client is not configured");
    }
    ChatClient client = builder.clone().build();
    String prompt = buildPrompt(command, openai);
    var response = client.prompt().user(prompt).call();

    String text = response.chatResponse().getResult().getOutput().getText();
    String json = extractJson(text);
    LlmArtifactResponse parsed;
    try {
      parsed = objectMapper.readValue(json, LlmArtifactResponse.class);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to parse OpenAI artifact response", ex);
    }
    List<ArtifactOperation> operations = normalizeOperations(parsed.operations(), openai, command.operationsLimit());
    return new GenerationResult(
        parsed.summary() == null ? "" : parsed.summary().trim(),
        operations,
        parsed.warnings() == null ? List.of() : List.copyOf(parsed.warnings()));
  }

  private List<ArtifactOperation> normalizeOperations(
      List<LlmArtifactOperation> rawOperations,
      CodingAssistantProperties.OpenAiProperties openai,
      int operationsLimit) {
    if (rawOperations == null || rawOperations.isEmpty()) {
      return List.of();
    }
    List<ArtifactOperation> normalized = new ArrayList<>();
    int maxOperations = operationsLimit <= 0 ? openai.getMaxOperations() : operationsLimit;
    for (LlmArtifactOperation raw : rawOperations) {
      if (raw == null || !StringUtils.hasText(raw.path()) || !StringUtils.hasText(raw.action())) {
        continue;
      }
      ArtifactAction action = ArtifactAction.from(raw.action());
      String path = raw.path().trim();
      String body = raw.contents() == null ? "" : raw.contents();
      validateOperationLimits(path, body, openai);
      normalized.add(
          new ArtifactOperation(
              path,
              action,
              raw.languageHint() == null ? "" : raw.languageHint().trim(),
              body,
              raw.insertBefore() == null ? "" : raw.insertBefore()));
      if (normalized.size() >= maxOperations) {
        break;
      }
    }
    if (normalized.isEmpty()) {
      throw new IllegalStateException("OpenAI did not return any valid artifact operations");
    }
    return List.copyOf(normalized);
  }

  private void validateOperationLimits(
      String path, String contents, CodingAssistantProperties.OpenAiProperties openai) {
    if (contents == null) {
      return;
    }
    int lineLimit = Math.max(1, openai.getMaxFileLines());
    long lines = contents.lines().count();
    if (lines > lineLimit) {
      throw new IllegalArgumentException(
          "Operation for "
              + path
              + " exceeds maxFileLines limit: "
              + lines
              + " > "
              + lineLimit);
    }
    int byteLimit = Math.max(1024, openai.getMaxFileBytes());
    int bytes = contents.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > byteLimit) {
      throw new IllegalArgumentException(
          "Operation for "
              + path
              + " exceeds maxFileBytes limit: "
              + bytes
              + " > "
              + byteLimit);
    }
  }

  private String buildPrompt(Command command, CodingAssistantProperties.OpenAiProperties openai) {
    StringJoiner joiner = new StringJoiner("\n");
    joiner.add(
        """
You are GPT-4o Mini acting as a coding file generator for AI Advent Coding MCP.
Respond strictly with JSON matching the schema:
{
  "summary": "short ru summary of the modifications",
  "operations": [
    {
      "path": "relative/path",
      "action": "create|overwrite|append|insert",
      "languageHint": "typescript|java|markdown|...",
      "contents": "complete UTF-8 file fragment to write",
      "insertBefore": "optional exact substring marker for insert action"
    }
  ],
  "warnings": ["optional warnings or TODOs"]
}
Rules:
- Do not wrap JSON in markdown fences and do not include explanations outside JSON.
- Respect workspace boundaries: touch only requested files, stay within target paths where possible.
- Limit operations to """
            + command.operationsLimit()
            + """
 files; each file ≤ """
            + openai.getMaxFileLines()
            + " lines and ≤ "
            + openai.getMaxFileBytes()
            + " bytes.\n- Output UTF-8 text only (no base64, no binary).");
    joiner.add("");
    joiner.add("## Workspace");
    joiner.add("workspaceId: " + command.workspaceId());
    if (!command.targetPaths().isEmpty()) {
      joiner.add("targetPaths: " + String.join(", ", command.targetPaths()));
    }
    if (!command.forbiddenPaths().isEmpty()) {
      joiner.add("forbiddenPaths: " + String.join(", ", command.forbiddenPaths()));
    }
    joiner.add("");
    joiner.add("## Operator instructions");
    joiner.add(command.instructions());
    joiner.add("");
    if (!command.contextSnippets().isEmpty()) {
      joiner.add("## Context files");
      for (ContextSnippet snippet : command.contextSnippets()) {
        joiner.add(describeSnippet(snippet));
      }
    }
    return joiner.toString();
  }

  private String describeSnippet(ContextSnippet snippet) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("### File: ")
        .append(snippet.path())
        .append(" (binary=")
        .append(snippet.binary())
        .append(", truncated=")
        .append(snippet.truncated())
        .append(")");
    if (snippet.binary()) {
      builder.append("\nBinary preview omitted; base64 length=")
          .append(
              StringUtils.hasText(snippet.base64())
                  ? snippet.base64().length()
                  : 0);
      return builder.toString();
    }
    builder.append("\n```");
    builder.append(snippet.content());
    builder.append("\n```");
    return builder.toString();
  }

  private String extractJson(String raw) {
    if (!StringUtils.hasText(raw)) {
      return raw;
    }
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start >= 0 && end >= start) {
      return raw.substring(start, end + 1);
    }
    return raw;
  }

  private CodingAssistantProperties.OpenAiProperties requireOpenAi() {
    CodingAssistantProperties.OpenAiProperties openai = properties.getOpenai();
    if (openai == null || !openai.isEnabled()) {
      throw new IllegalStateException("Coding OpenAI generator is disabled");
    }
    return openai;
  }

  record Command(
      String workspaceId,
      Path workspacePath,
      String instructions,
      List<String> targetPaths,
      List<String> forbiddenPaths,
      List<ContextSnippet> contextSnippets,
      int operationsLimit) {}

  record GenerationResult(
      String summary, List<ArtifactOperation> operations, List<String> warnings) {}

  record ArtifactOperation(
      String path, ArtifactAction action, String languageHint, String contents, String insertBefore) {}

  enum ArtifactAction {
    CREATE,
    OVERWRITE,
    APPEND,
    INSERT;

    static ArtifactAction from(String value) {
      if (!StringUtils.hasText(value)) {
        return OVERWRITE;
      }
      return switch (value.trim().toLowerCase()) {
        case "create" -> CREATE;
        case "append" -> APPEND;
        case "insert" -> INSERT;
        default -> OVERWRITE;
      };
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LlmArtifactResponse(
      String summary, List<LlmArtifactOperation> operations, List<String> warnings) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LlmArtifactOperation(
      String path, String action, String languageHint, String contents, String insertBefore) {}
}
