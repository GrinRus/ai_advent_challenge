package com.aiadvent.mcp.backend.github.rag.chunking;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RepoRagChunker {

  private final GitHubRagProperties properties;
  private final LineChunkingStrategy lineStrategy = new LineChunkingStrategy();
  private final TokenChunkingStrategy tokenStrategy = new TokenChunkingStrategy();
  private final SemanticCodeChunker semanticStrategy = new SemanticCodeChunker(lineStrategy);

  public RepoRagChunker(GitHubRagProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public List<Chunk> chunk(ChunkableFile file) {
    return chunk(file, properties.getChunking().getStrategy());
  }

  public List<Chunk> chunk(ChunkableFile file, GitHubRagProperties.Strategy strategy) {
    Objects.requireNonNull(file, "file");
    ChunkingStrategy delegate = selectStrategy(strategy);
    return delegate.chunk(new ChunkingContext(file, properties.getChunking(), strategy));
  }

  private ChunkingStrategy selectStrategy(GitHubRagProperties.Strategy strategy) {
    return switch (strategy) {
      case BYTE, LINE -> lineStrategy;
      case TOKEN -> tokenStrategy;
      case SEMANTIC ->
          properties.getChunking().getSemantic().isEnabled()
              ? semanticStrategy
              : lineStrategy;
    };
  }
}
