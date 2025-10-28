package com.aiadvent.backend.mcp.agentops;

public record ListAgentsInput(String query, Boolean activeOnly, Integer limit) {
  public ListAgentsInput {
    if (limit != null && limit < 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }
}

