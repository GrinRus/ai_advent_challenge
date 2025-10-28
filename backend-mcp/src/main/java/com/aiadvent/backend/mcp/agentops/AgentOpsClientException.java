package com.aiadvent.backend.mcp.agentops;

public class AgentOpsClientException extends RuntimeException {

  AgentOpsClientException(String message) {
    super(message);
  }

  AgentOpsClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
