package com.aiadvent.mcp.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class GitHubRagPropertiesTest {

  @Test
  void bindsProfilesAndResolvesDefault() {
    Map<String, String> props = new HashMap<>();
    props.put("github.rag.default-profile", "aggressive");
    props.put("github.rag.parameter-profiles[0].name", "balanced");
    props.put("github.rag.parameter-profiles[0].top-k", "8");
    props.put("github.rag.parameter-profiles[0].top-k-per-query", "8");
    props.put("github.rag.parameter-profiles[0].min-score", "0.6");
    props.put("github.rag.parameter-profiles[0].multi-query.enabled", "true");
    props.put("github.rag.parameter-profiles[0].multi-query.queries", "3");
    props.put("github.rag.parameter-profiles[0].multi-query.max-queries", "4");
    props.put("github.rag.parameter-profiles[0].neighbor.strategy", "linear");
    props.put("github.rag.parameter-profiles[0].neighbor.radius", "1");
    props.put("github.rag.parameter-profiles[0].neighbor.limit", "4");
    props.put("github.rag.parameter-profiles[1].name", "aggressive");
    props.put("github.rag.parameter-profiles[1].top-k", "12");
    props.put("github.rag.parameter-profiles[1].multi-query.enabled", "true");
    props.put("github.rag.parameter-profiles[1].multi-query.queries", "4");
    props.put("github.rag.parameter-profiles[1].multi-query.max-queries", "6");
    props.put("github.rag.parameter-profiles[1].neighbor.strategy", "CALL_GRAPH");
    props.put("github.rag.parameter-profiles[1].neighbor.radius", "2");
    props.put("github.rag.parameter-profiles[1].neighbor.limit", "10");

    GitHubRagProperties properties = bind(props);
    properties.afterPropertiesSet();

    GitHubRagProperties.ResolvedRagParameterProfile defaultProfile =
        properties.resolveProfile(null);
    assertThat(defaultProfile.name()).isEqualTo("aggressive");
    assertThat(defaultProfile.topK()).isEqualTo(12);

    GitHubRagProperties.ResolvedRagParameterProfile balanced =
        properties.resolveProfile("BALANCED");
    assertThat(balanced.minScore()).isEqualTo(0.6d);
    assertThat(balanced.neighbor().strategy()).isEqualTo("LINEAR");
    assertThat(properties.getResolvedDefaultProfile()).isEqualTo("aggressive");
  }

  @Test
  void rejectsDuplicateProfileNames() {
    Map<String, String> props = new HashMap<>();
    props.put("github.rag.parameter-profiles[0].name", "dup");
    props.put("github.rag.parameter-profiles[1].name", "DUP");

    GitHubRagProperties properties = bind(props);
    assertThatThrownBy(properties::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate RAG profile name");
  }

  private GitHubRagProperties bind(Map<String, String> props) {
    Binder binder = new Binder(new MapConfigurationPropertySource(props));
    return binder.bind("github.rag", Bindable.of(GitHubRagProperties.class)).get();
  }
}
