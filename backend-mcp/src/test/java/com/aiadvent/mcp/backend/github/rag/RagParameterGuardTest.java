package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RagParameterGuardTest {

  private GitHubRagProperties properties;
  private RagParameterGuard guard;

  @BeforeEach
  void setUp() {
    properties = new GitHubRagProperties();
    guard = new RagParameterGuard(properties);
  }

  @Test
  void clampsOutOfRangeValues() {
    GitHubRagProperties.ResolvedRagParameterProfile profile =
        new GitHubRagProperties.ResolvedRagParameterProfile(
            "aggressive",
            100,
            80,
            1.5d,
            Map.of("java", 2.0d),
            100,
            null,
            10.0d,
            new GitHubRagProperties.ResolvedRagParameterProfile.ResolvedMultiQuery(true, 10, 12),
            new GitHubRagProperties.ResolvedRagParameterProfile.ResolvedNeighbor("CALL_GRAPH", 10, 500),
            0.3d,
            "overview",
            List.of("overview"));

    RagParameterGuard.GuardResult result = guard.apply(profile);

    RagParameterGuard.ResolvedSearchPlan plan = result.plan();
    assertThat(plan.topK()).isEqualTo(40);
    assertThat(plan.topKPerQuery()).isEqualTo(40);
    assertThat(plan.minScore()).isEqualTo(0.99d);
    assertThat(plan.neighbor().limit()).isEqualTo(12);
    assertThat(plan.neighbor().radius()).isEqualTo(5);
    assertThat(plan.codeAwareHeadMultiplier()).isEqualTo(4.0d);
    assertThat(plan.multiQuery().queries()).isEqualTo(6);
    assertThat(plan.multiQuery().maxQueries()).isEqualTo(6);
    assertThat(plan.profileName()).isEqualTo("aggressive");
    assertThat(result.warnings())
        .anyMatch(message -> message.contains("topK"))
        .anyMatch(message -> message.contains("neighbor.limit"));
  }
}
