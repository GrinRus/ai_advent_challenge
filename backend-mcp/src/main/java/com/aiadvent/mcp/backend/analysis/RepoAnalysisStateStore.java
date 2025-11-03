package com.aiadvent.mcp.backend.analysis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RepoAnalysisStateStore {

  private static final Logger log = LoggerFactory.getLogger(RepoAnalysisStateStore.class);

  private final Path root;
  private final ObjectMapper objectMapper;

  RepoAnalysisStateStore(com.aiadvent.mcp.backend.config.RepoAnalysisProperties properties) {
    this.root = properties.stateRootPath();
    this.objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try {
      Files.createDirectories(root);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create repo analysis state root: " + root, ex);
    }
  }

  Optional<RepoAnalysisState> load(String analysisId) {
    Path statePath = statePath(analysisId);
    if (!Files.exists(statePath)) {
      return Optional.empty();
    }
    try {
      RepoAnalysisState state =
          objectMapper.readValue(Files.readAllBytes(statePath), RepoAnalysisState.class);
      return Optional.of(state);
    } catch (IOException ex) {
      log.warn("Failed to read analysis state {}: {}", analysisId, ex.getMessage());
      return Optional.empty();
    }
  }

  void save(RepoAnalysisState state) {
    state.setUpdatedAt(Instant.now());
    Path statePath = statePath(state.getAnalysisId());
    Path tempPath = statePath.resolveSibling(statePath.getFileName() + ".tmp");
    try {
      byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
      Files.write(tempPath, bytes);
      try {
        Files.move(
            tempPath,
            statePath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
        Files.move(tempPath, statePath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ex) {
      try {
        Files.deleteIfExists(tempPath);
      } catch (IOException ignore) {
        // ignore
      }
      throw new IllegalStateException(
          "Failed to persist analysis state %s".formatted(state.getAnalysisId()), ex);
    }
  }

  void delete(String analysisId) {
    Path statePath = statePath(analysisId);
    try {
      Files.deleteIfExists(statePath);
    } catch (IOException ex) {
      log.warn("Failed to delete state {}: {}", analysisId, ex.getMessage());
    }
  }

  private Path statePath(String analysisId) {
    if (analysisId == null || analysisId.isBlank()) {
      throw new IllegalArgumentException("analysisId must not be blank");
    }
    return root.resolve("%s.json".formatted(analysisId));
  }
}
