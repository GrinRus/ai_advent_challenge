package com.aiadvent.backend.flow.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GitHubResolverPayloadTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parseResolvedRepositoryPayload() {
    String json =
        """
        {
          "status": "resolved",
          "githubTarget": {
            "type": "repository",
            "owner": "ai-advent",
            "repository": "demo",
            "ref": "main",
            "url": "https://github.com/ai-advent/demo"
          }
        }
        """;

    GitHubResolverPayload payload = GitHubResolverPayload.parse(objectMapper, json);
    assertThat(payload.status()).isEqualTo(GitHubResolverStatus.RESOLVED);
    assertThat(payload.target()).isNotNull();
    assertThat(payload.target().owner()).isEqualTo("ai-advent");
    assertThat(payload.target().repository()).isEqualTo("demo");
    assertThat(payload.target().ref()).isEqualTo("main");
    assertThat(payload.target().sourceUrl()).isEqualTo("https://github.com/ai-advent/demo");
    assertThat(payload.clarificationPrompt()).isNull();
  }

  @Test
  void parseClarificationPayload() {
    String json =
        """
        {
          "status": "needs_clarification",
          "clarification": {
            "prompt": "Specify the branch you want to analyse.",
            "reason": "Unable to determine branch from context",
            "missing": ["branch"]
          }
        }
        """;

    GitHubResolverPayload payload = GitHubResolverPayload.parse(objectMapper, json);
    assertThat(payload.status()).isEqualTo(GitHubResolverStatus.NEEDS_CLARIFICATION);
    assertThat(payload.target()).isNull();
    assertThat(payload.clarificationPrompt()).contains("Specify the branch");
    assertThat(payload.clarificationReason()).contains("Unable to determine");
    assertThat(payload.missingFields()).contains("branch");
  }

  @Test
  void invalidResolvedPayloadWithoutTarget() {
    String json =
        """
        {
          "status": "resolved",
          "githubTarget": {}
        }
        """;

    GitHubResolverPayload payload = GitHubResolverPayload.parse(objectMapper, json);
    assertThat(payload.status()).isEqualTo(GitHubResolverStatus.INVALID);
  }

  @Test
  void parseFailsOnNonJsonContent() {
    assertThatThrownBy(() -> GitHubResolverPayload.parse(objectMapper, "not-json"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

