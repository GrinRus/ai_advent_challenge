package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TreeSitterGradleTasksTest {

  private static String gradleVersion;

  @BeforeAll
  static void determineGradleVersion() throws IOException {
    Path wrapperProperties = Path.of("gradle/wrapper/gradle-wrapper.properties");
    if (!Files.exists(wrapperProperties)) {
      throw new IllegalStateException("Gradle wrapper properties not found");
    }
    try (Stream<String> lines = Files.lines(wrapperProperties)) {
      gradleVersion =
          lines.filter(line -> line.startsWith("distributionUrl"))
              .map(TreeSitterGradleTasksTest::extractVersion)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("distributionUrl missing"));
    }
  }

  private static String extractVersion(String distributionLine) {
    int start = distributionLine.indexOf("gradle-");
    if (start < 0) {
      throw new IllegalStateException("Cannot parse gradle version from: " + distributionLine);
    }
    start += "gradle-".length();
    int end = distributionLine.indexOf('-', start);
    if (end < 0) {
      end = distributionLine.indexOf('.', start);
    }
    if (end < 0) {
      throw new IllegalStateException("Cannot parse gradle version from: " + distributionLine);
    }
    return distributionLine.substring(start, end);
  }

  @Test
  void treeSitterVerifyBuildsLibraries() throws IOException {
    File projectDir = Path.of("").toAbsolutePath().normalize().toFile();
    BuildResult result =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("treeSitterVerify", "-q")
            .withGradleVersion(gradleVersion)
            .forwardOutput()
            .build();

    assertThat(result.getOutput()).contains("Tree-sitter libraries available");

    TreeSitterPlatform platform = TreeSitterPlatform.detect();
    Path libDir =
        projectDir.toPath()
            .resolve("build")
            .resolve("treesitter")
            .resolve(platform.os())
            .resolve(platform.arch());
    assertThat(libDir).exists();
    try (Stream<Path> files = Files.list(libDir)) {
      assertThat(files.findAny()).isPresent();
    }
  }
}
