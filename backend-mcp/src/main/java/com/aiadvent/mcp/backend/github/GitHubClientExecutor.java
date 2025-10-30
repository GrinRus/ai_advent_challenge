package com.aiadvent.mcp.backend.github;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class GitHubClientExecutor {

  private static final Logger log = LoggerFactory.getLogger(GitHubClientExecutor.class);

  private final GitHubClientFactory clientFactory;
  private final GitHubTokenManager tokenManager;

  GitHubClientExecutor(GitHubClientFactory clientFactory, GitHubTokenManager tokenManager) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
  }

  <T> T execute(Function<GitHub, T> operation) {
    Objects.requireNonNull(operation, "operation");
    String token = tokenManager.currentToken();
    try {
      GitHub client = clientFactory.createPatClient(token);
      return operation.apply(client);
    } catch (IOException ex) {
      throw new GitHubClientException("Failed to execute GitHub API call", ex);
    }
  }

  void executeVoid(Consumer<GitHub> operation) {
    execute(
        gh -> {
          operation.accept(gh);
          return null;
        });
  }
}
