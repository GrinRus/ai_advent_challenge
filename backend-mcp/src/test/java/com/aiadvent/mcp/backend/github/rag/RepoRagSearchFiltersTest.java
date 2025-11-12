package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepoRagSearchFiltersTest {

  @Test
  void normalizesLanguagesAndGlobs() {
    RepoRagSearchFilters filters =
        new RepoRagSearchFilters(List.of("Python", " python ", "JS"), List.of("**/*", "src/**"));

    assertThat(filters.languages()).containsExactly("python", "js");
    assertThat(filters.hasPathGlobs()).isTrue();
    assertThat(filters.pathGlobs()).containsExactly("src/**");
  }
}
