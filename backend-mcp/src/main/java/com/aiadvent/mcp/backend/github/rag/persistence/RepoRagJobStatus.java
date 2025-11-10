package com.aiadvent.mcp.backend.github.rag.persistence;

public enum RepoRagJobStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED;

  public static RepoRagJobStatus fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Status value must not be null");
    }
    return RepoRagJobStatus.valueOf(value.trim().toUpperCase());
  }
}
