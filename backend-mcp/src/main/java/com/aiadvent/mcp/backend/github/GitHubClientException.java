package com.aiadvent.mcp.backend.github;

class GitHubClientException extends RuntimeException {

  GitHubClientException(String message) {
    super(message);
  }

  GitHubClientException(String message, Throwable cause) {
    super(message, cause);
  }
}

