package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.core.io.DefaultResourceLoader;

class TreeSitterLibraryLoaderSmokeTest {

  @Test
  @EnabledOnOs({OS.MAC, OS.LINUX})
  void loadsJavaLanguageFromClasspathResources() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getAst().setEnabled(true);
    properties.getAst().setLanguages(List.of("java"));

    TreeSitterLibraryLoader loader =
        new TreeSitterLibraryLoader(properties, new DefaultResourceLoader());
    assumeTrue(loader.ensureCoreLibraryLoaded(), "libjava-tree-sitter not available");

    Optional<TreeSitterLibraryLoader.LoadedLibrary> loaded = loader.loadLanguage("java");

    assertThat(loaded).isPresent();
    assertThat(Files.exists(loaded.get().path())).isTrue();
  }
}
