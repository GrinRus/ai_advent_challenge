package com.aiadvent.backend.flow.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

public record GitHubTarget(
    GitHubTargetType type,
    String owner,
    String repository,
    String ref,
    Integer pullRequestNumber,
    String headRef,
    String baseRef,
    String sourceUrl) {

  public GitHubTarget {
    type = type != null ? type : GitHubTargetType.REPOSITORY;
    owner = normalize(owner);
    repository = normalize(repository);
    ref = normalize(ref);
    headRef = normalize(headRef);
    baseRef = normalize(baseRef);
    sourceUrl = normalizeUrl(sourceUrl);

    if (!StringUtils.hasText(owner)) {
      throw new IllegalArgumentException("GitHub target owner must be provided");
    }
    if (!StringUtils.hasText(repository)) {
      throw new IllegalArgumentException("GitHub target repository must be provided");
    }
    if (type == GitHubTargetType.PULL_REQUEST && (pullRequestNumber == null || pullRequestNumber <= 0)) {
      throw new IllegalArgumentException("Pull request target must declare positive pullRequestNumber");
    }
  }

  public ObjectNode toJson(ObjectMapper mapper) {
    ObjectMapper effectiveMapper = mapper != null ? mapper : new ObjectMapper();
    ObjectNode node = effectiveMapper.createObjectNode();
    node.put("type", type.name());
    node.put("owner", owner);
    node.put("repository", repository);
    if (StringUtils.hasText(ref)) {
      node.put("ref", ref);
    }
    if (pullRequestNumber != null && pullRequestNumber > 0) {
      node.put("pullRequestNumber", pullRequestNumber);
    }
    if (StringUtils.hasText(headRef)) {
      node.put("headRef", headRef);
    }
    if (StringUtils.hasText(baseRef)) {
      node.put("baseRef", baseRef);
    }
    if (StringUtils.hasText(sourceUrl)) {
      node.put("sourceUrl", sourceUrl);
    }
    return node;
  }

  public static GitHubTarget fromJson(ObjectMapper mapper, JsonNode node) {
    if (node == null || node.isNull() || !node.isObject()) {
      return null;
    }
    GitHubTargetType type = GitHubTargetType.fromString(node.path("type").asText(null));
    if (type == null) {
      type = GitHubTargetType.fromString(node.path("targetType").asText(null));
    }
    String owner = text(node, "owner");
    String repository = text(node, "repository");
    if (!StringUtils.hasText(repository)) {
      repository = text(node, "repo");
    }
    String ref = text(node, "ref");
    if (!StringUtils.hasText(ref)) {
      ref = text(node, "branch");
    }
    Integer pullRequestNumber = null;
    if (node.hasNonNull("pullRequestNumber")) {
      pullRequestNumber = node.get("pullRequestNumber").asInt();
    } else if (node.hasNonNull("pullRequest")) {
      JsonNode prNode = node.get("pullRequest");
      if (prNode.hasNonNull("number")) {
        pullRequestNumber = prNode.get("number").asInt();
      }
      if (!StringUtils.hasText(ref)) {
        ref = text(prNode, "ref");
      }
      if (!StringUtils.hasText(ref)) {
        ref = text(prNode, "headRef");
      }
    }
    String headRef = text(node, "headRef");
    String baseRef = text(node, "baseRef");
    JsonNode prNode = node.get("pullRequest");
    if (prNode != null && prNode.isObject()) {
      if (!StringUtils.hasText(headRef)) {
        headRef = text(prNode, "headRef");
      }
      if (!StringUtils.hasText(baseRef)) {
        baseRef = text(prNode, "baseRef");
      }
    }
    String sourceUrl = text(node, "url");
    if (!StringUtils.hasText(sourceUrl)) {
      sourceUrl = text(node, "sourceUrl");
    }

    if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
      return null;
    }
    GitHubTargetType resolvedType =
        type != null
            ? type
            : (pullRequestNumber != null && pullRequestNumber > 0
                ? GitHubTargetType.PULL_REQUEST
                : GitHubTargetType.REPOSITORY);
    return new GitHubTarget(resolvedType, owner, repository, ref, pullRequestNumber, headRef, baseRef, sourceUrl);
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    String value = node.get(field).asText();
    return normalize(value);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizeUrl(String value) {
    String normalized = normalize(value);
    if (normalized == null) {
      return null;
    }
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}

