package com.aiadvent.backend.flow.api;

public record ValidationIssue(String path, String message, IssueSeverity severity) {

  public enum IssueSeverity {
    ERROR,
    WARNING,
    INFO
  }
}

