package com.aiadvent.backend.flow.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

public record GitHubResolverPayload(
    GitHubResolverStatus status,
    GitHubTarget target,
    String clarificationPrompt,
    String clarificationReason,
    List<String> missingFields,
    JsonNode raw) {

  public static GitHubResolverPayload parse(ObjectMapper mapper, String content) {
    if (!StringUtils.hasText(content)) {
      throw new IllegalArgumentException("GitHub resolver returned an empty response");
    }
    try {
      JsonNode node = mapper.readTree(content);
      if (node == null || node.isNull() || !node.isObject()) {
        throw new IllegalArgumentException("GitHub resolver response must be a JSON object");
      }
      JsonNode root = node;

      JsonNode outcomeNode = root.hasNonNull("outcome") ? root.get("outcome") : root;
      String statusValue = outcomeNode.path("status").asText(null);
      if (!StringUtils.hasText(statusValue)) {
        statusValue = root.path("status").asText(null);
      }
      GitHubResolverStatus status = GitHubResolverStatus.fromString(statusValue);

      GitHubTarget target = GitHubTarget.fromJson(mapper, outcomeNode.path("githubTarget"));
      if (target == null) {
        target = GitHubTarget.fromJson(mapper, outcomeNode.path("target"));
      }

      String clarificationPrompt = readText(outcomeNode, "clarificationPrompt");
      if (!StringUtils.hasText(clarificationPrompt)) {
        JsonNode clarificationNode = outcomeNode.path("clarification");
        clarificationPrompt = readText(clarificationNode, "prompt");
      }
      String clarificationReason = readText(outcomeNode, "clarificationReason");
      if (!StringUtils.hasText(clarificationReason)) {
        JsonNode clarificationNode = outcomeNode.path("clarification");
        clarificationReason = readText(clarificationNode, "reason");
      }

      List<String> missing = new ArrayList<>();
      collectMissing(outcomeNode.path("missingFields"), missing);
      collectMissing(outcomeNode.path("clarification").path("missing"), missing);
      collectMissing(outcomeNode.path("clarification").path("missingFields"), missing);

      if (status == GitHubResolverStatus.RESOLVED && target == null) {
        status = GitHubResolverStatus.INVALID;
      }

      return new GitHubResolverPayload(status, target, clarificationPrompt, clarificationReason, List.copyOf(missing), root);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Failed to parse GitHub resolver JSON: " + exception.getMessage(), exception);
    }
  }

  private static String readText(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    String value = node.get(field).asText();
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static void collectMissing(JsonNode node, List<String> target) {
    if (node == null || node.isNull() || target == null) {
      return;
    }
    if (node.isArray()) {
      for (JsonNode element : node) {
        if (element != null && element.isTextual()) {
          String text = element.asText();
          if (StringUtils.hasText(text)) {
            target.add(text.trim());
          }
        }
      }
    } else if (node.isTextual() && StringUtils.hasText(node.asText())) {
      target.add(node.asText().trim());
    }
  }
}

