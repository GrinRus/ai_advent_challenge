package com.aiadvent.backend.flow.github;

public enum GitHubTargetType {
  REPOSITORY,
  PULL_REQUEST;

  public static GitHubTargetType fromString(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return switch (value.trim().toLowerCase()) {
      case "repo", "repository" -> REPOSITORY;
      case "pr", "pull_request", "pull-request", "pullrequest" -> PULL_REQUEST;
      default -> null;
    };
  }
}

