package com.aiadvent.mcp.backend.coding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ClaudeCliPatchGenerator implements PatchGenerator {

  private static final Logger log = LoggerFactory.getLogger(ClaudeCliPatchGenerator.class);

  private final ClaudeCliService cliService;
  private final CodingAssistantProperties properties;
  private final ObjectMapper objectMapper;

  ClaudeCliPatchGenerator(
      ClaudeCliService cliService,
      CodingAssistantProperties properties,
      ObjectMapper objectMapper) {
    this.cliService = Objects.requireNonNull(cliService, "cliService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public GenerationResult generate(GeneratePatchCommand command) {
    CodingAssistantProperties.ClaudeCliProperties cliProps =
        Objects.requireNonNull(properties.getClaude(), "claude");
    if (!cliProps.isEnabled()) {
      throw new IllegalStateException("Claude CLI generator is disabled");
    }
    String prompt = buildPrompt(command);
    log.debug(
        "Invoking Claude CLI for patch {} (prompt bytes={}, context files={})",
        command.patchId(),
        prompt.getBytes(StandardCharsets.UTF_8).length,
        command.contextSnippets().size());
    ClaudeCliService.ClaudeCliInvocation invocation =
        cliService.invoke(command.workspacePath(), prompt);
    CliPatchResponse response = parseResponse(invocation.stdout());
    validateResponse(response);
    PatchAnnotations annotations =
        new PatchAnnotations(
            response.files() == null ? List.of() : response.files(),
            response.risks() == null ? List.of() : response.risks(),
            List.of());
    PatchUsage usage =
        response.usage() == null
            ? PatchUsage.empty()
            : new PatchUsage(
                Math.max(0, response.usage().promptTokens()),
                Math.max(0, response.usage().completionTokens()));
    boolean requiresManualReview =
        response.requiresManualReview() != null && response.requiresManualReview();
    return new GenerationResult(
        response.summary(),
        response.diff(),
        annotations,
        usage,
        requiresManualReview);
  }

  private String buildPrompt(GeneratePatchCommand command) {
    StringJoiner joiner = new StringJoiner("\n");
    joiner.add(
        """
You are Claude Code CLI running for AI Advent coding MCP. Generate a unified git diff that applies the operator instructions strictly for the provided workspace.\s
Respond strictly with JSON matching the structure:
{
  "summary": "short bullet list or sentences",
  "diff": "git-style diff (diff --git ...)",
  "files": ["relative/path"],
  "risks": ["list of warnings or blockers"],
  "warnings": ["optional remarks"],
  "usage": {"promptTokens": number, "completionTokens": number},
  "requiresManualReview": true|false
}
Do not wrap the JSON in markdown fences and do not include extra explanations.
""");
    joiner.add("## Workspace");
    joiner.add("workspaceId: " + command.workspaceId());
    joiner.add("patchId: " + command.patchId());
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
      builder.append("\nBinary file preview omitted; base64 length=")
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

  private CliPatchResponse parseResponse(String stdout) {
    if (!StringUtils.hasText(stdout)) {
      throw new IllegalStateException("Claude CLI returned empty response");
    }
    String cleanedOutput = AnsiCleaner.strip(stdout);
    String json = extractJson(cleanedOutput);
    try {
      return objectMapper.readValue(json, CliPatchResponse.class);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to parse Claude CLI response: " + json, ex);
    }
  }

  private String extractJson(String raw) {
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start >= 0 && end >= start) {
      return raw.substring(start, end + 1);
    }
    return raw;
  }

  private void validateResponse(CliPatchResponse response) {
    if (response == null) {
      throw new IllegalStateException("Claude CLI response is null");
    }
    if (!StringUtils.hasText(response.diff())) {
      throw new IllegalStateException("Claude CLI response is missing diff");
    }
    if (!StringUtils.hasText(response.summary())) {
      throw new IllegalStateException("Claude CLI response is missing summary");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CliPatchResponse(
      String summary,
      String diff,
      List<String> files,
      List<String> risks,
      List<String> warnings,
      Usage usage,
      @JsonProperty("requiresManualReview") Boolean requiresManualReview) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Usage(int promptTokens, int completionTokens) {}
}
