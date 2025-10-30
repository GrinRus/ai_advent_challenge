package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubTokenManager {

  private final GitHubBackendProperties properties;

  GitHubTokenManager(GitHubBackendProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  String currentToken() {
    String pat = properties.getPersonalAccessToken();
    if (!StringUtils.hasText(pat)) {
      throw new IllegalStateException(
          "GitHub PAT must be provided via github.backend.personal-access-token (env `GITHUB_PAT`).");
    }
    return pat.trim();
  }
}

