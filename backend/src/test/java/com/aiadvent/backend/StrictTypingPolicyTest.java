package com.aiadvent.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StrictTypingPolicyTest {

  @Test
  void noMapStringObjectInMainSources() throws IOException {
    Path baseDir = Path.of("src", "main", "java");
    try (Stream<Path> stream = Files.walk(baseDir)) {
      List<Path> offenders =
          stream
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".java"))
              .filter(
                  path -> {
                    try {
                      String content = Files.readString(path, StandardCharsets.UTF_8);
                      if (content.contains("Map<String, Object>")) {
                        String normalised = path.toString().replace('\\', '/');
                        if (normalised.contains("chat/memory/model/ChatMemoryMessageMetadata")
                            || normalised.contains("chat/provider/model/ChatAdvisorContext")
                            || normalised.contains("flow/telemetry/FlowTraceFormatter")) {
                          return false;
                        }
                        return true;
                      }
                      return false;
                    } catch (IOException exception) {
                      throw new IllegalStateException("Failed to read " + path, exception);
                    }
                  })
              .collect(Collectors.toList());

      assertThat(offenders)
          .describedAs("Typed DTOs should be used instead of Map<String, Object>")
          .isEmpty();
    }
  }
}
