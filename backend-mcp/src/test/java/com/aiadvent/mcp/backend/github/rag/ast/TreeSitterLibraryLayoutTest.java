package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

class TreeSitterLibraryLayoutTest {

  @Test
  void hasRuntimeLibrariesOnClasspath() {
    TreeSitterPlatform platform = TreeSitterPlatform.detect();
    String basePath = "treesitter/" + platform.os() + "/" + platform.arch() + "/";
    ClassPathResource core =
        new ClassPathResource(basePath + platform.libraryFileName("java-tree-sitter"));
    boolean hasCore = core.exists();
    boolean hasAnyLanguageLib =
        Arrays.stream(TreeSitterLanguage.values())
            .map(language -> new ClassPathResource(basePath + language.resolveLibraryFile(platform)))
            .anyMatch(ClassPathResource::exists);

    assertThat(hasCore || hasAnyLanguageLib)
        .as("runtime or language libs present under %s", basePath)
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
