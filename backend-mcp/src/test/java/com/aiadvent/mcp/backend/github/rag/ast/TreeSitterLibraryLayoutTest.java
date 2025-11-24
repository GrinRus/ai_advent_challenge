package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

class TreeSitterLibraryLayoutTest {

  @Test
  void hasCoreRuntimeLibraryOnClasspath() {
    TreeSitterPlatform platform = TreeSitterPlatform.detect();
    String resourcePath =
        "treesitter/" + platform.os() + "/" + platform.arch() + "/" + platform.libraryFileName("java-tree-sitter");
    ClassPathResource resource = new ClassPathResource(resourcePath);

    assertThat(resource.exists())
        .as("core lib present at %s", resourcePath)
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(TreeSitterLanguage.class)
  void hasCompiledLanguageLibraries(TreeSitterLanguage language) {
    TreeSitterPlatform platform = TreeSitterPlatform.detect();
    String fileName = language.resolveLibraryFile(platform);
    String resourcePath = "treesitter/" + platform.os() + "/" + platform.arch() + "/" + fileName;
    ClassPathResource resource = new ClassPathResource(resourcePath);

    assertThat(resource.exists())
        .as("language lib for %s at %s", language.id(), resourcePath)
        .isTrue();
  }
}
