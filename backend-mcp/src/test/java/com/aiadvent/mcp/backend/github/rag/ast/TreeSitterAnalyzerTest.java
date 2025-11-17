package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TreeSitterAnalyzerTest {

  private GitHubRagProperties properties;
  private TreeSitterLibraryLoader libraryLoader;
  private TreeSitterAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    properties = new GitHubRagProperties();
    properties.getAst().setEnabled(true);
    properties.getAst().setLanguages(List.of("java", "python"));
    properties.getAst().getHealth().setFailureThreshold(2);
    libraryLoader = mock(TreeSitterLibraryLoader.class);
    analyzer = new TreeSitterAnalyzer(properties, libraryLoader);
  }

  @Test
  void isDisabledWhenPropertySet() {
    properties.getAst().setEnabled(false);

    assertThat(analyzer.isEnabled()).isFalse();
  }

  @Test
  void ensureLanguageLoadedSkipsUnsupported() {
    properties.getAst().setLanguages(List.of("kotlin"));

    boolean result = analyzer.ensureLanguageLoaded("python");

    assertThat(result).isFalse();
    verify(libraryLoader, never()).loadLanguage("python");
  }

  @Test
  void ensureLanguageLoadedResetsFailuresOnSuccess() {
    analyzer.handleFailure("java");
    when(libraryLoader.loadLanguage("java"))
        .thenReturn(Optional.of(new TreeSitterLibraryLoader.LoadedLibrary(TreeSitterLanguage.JAVA, Path.of("/tmp/libtree-sitter-java.so"))));

    boolean result = analyzer.ensureLanguageLoaded("java");

    assertThat(result).isTrue();
    assertThat(analyzer.isEnabled()).isTrue();
    verify(libraryLoader).loadLanguage("java");
  }

  @Test
  void handleFailureDisablesAfterThreshold() {
    analyzer.handleFailure("java");
    assertThat(analyzer.isEnabled()).isTrue();

    analyzer.handleFailure("java");
    assertThat(analyzer.isEnabled()).isFalse();
  }

  @Test
  void ensureLanguageLoadedHandlesLoaderFailure() {
    when(libraryLoader.loadLanguage("python")).thenReturn(Optional.empty());

    boolean loaded = analyzer.ensureLanguageLoaded("python");

    assertThat(loaded).isFalse();
    assertThat(analyzer.isEnabled()).isTrue();
  }

  @Test
  void resetHealthRestoresAnalyzer() {
    analyzer.handleFailure("java");
    analyzer.handleFailure("java");
    assertThat(analyzer.isEnabled()).isFalse();

    analyzer.resetHealth();
    assertThat(analyzer.isEnabled()).isTrue();
  }
}
