package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import java.io.IOException;
import java.util.Objects;
import org.kohsuke.github.AbuseLimitHandler;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.RateLimitHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubClientFactory {

  private final GitHubBackendProperties properties;

  GitHubClientFactory(GitHubBackendProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  GitHub createInstallationClient(String installationToken) throws IOException {
    if (!StringUtils.hasText(installationToken)) {
      throw new IllegalArgumentException("installationToken must not be blank");
    }
    return configure(newBuilder()).withOAuthToken(installationToken.trim()).build();
  }

  GitHub createAppClient(String jwtToken) throws IOException {
    if (!StringUtils.hasText(jwtToken)) {
      throw new IllegalArgumentException("jwtToken must not be blank");
    }
    return configure(newBuilder()).withJwtToken(jwtToken.trim()).build();
  }

  private GitHubBuilder newBuilder() {
    return new GitHubBuilder();
  }

  private GitHubBuilder configure(GitHubBuilder builder) {
    builder.withRateLimitHandler(RateLimitHandler.WAIT);
    builder.withAbuseLimitHandler(AbuseLimitHandler.WAIT);
    if (StringUtils.hasText(properties.getBaseUrl())) {
      builder.withEndpoint(properties.getBaseUrl().trim());
    }
    return builder;
  }
}
