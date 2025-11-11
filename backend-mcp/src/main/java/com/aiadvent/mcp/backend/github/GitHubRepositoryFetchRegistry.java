package com.aiadvent.mcp.backend.github;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Keeps track of the most recent {@code github.repository_fetch} invocation so that dependent
 * tools (e.g. {@code repo.rag_search_simple}) can reuse the same repository context without
 * requiring the user to repeat owner/name parameters.
 */
@Component
public class GitHubRepositoryFetchRegistry {

  private final AtomicReference<LastFetchContext> lastFetch = new AtomicReference<>();

  public void record(LastFetchContext context) {
    if (context != null) {
      lastFetch.set(context);
    }
  }

  public Optional<LastFetchContext> latest() {
    return Optional.ofNullable(lastFetch.get());
  }

  public record LastFetchContext(
      String repoOwner,
      String repoName,
      String resolvedRef,
      String commitSha,
      String workspaceId,
      Instant fetchedAt) {}
}
