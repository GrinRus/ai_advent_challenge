package com.aiadvent.mcp.backend.agentops;

public class AgentOpsClientException extends RuntimeException {

  AgentOpsClientException(String message) {
    super(message);
  }

  AgentOpsClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
