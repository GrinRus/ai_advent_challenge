package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import com.aiadvent.mcp.backend.github.rag.chunking.ChunkableFile;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingStrategyTest {

  @Test
  void semanticChunkingAlignsWithFunctions() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getChunking().setStrategy(GitHubRagProperties.Strategy.SEMANTIC);
    properties.getChunking().getSemantic().setEnabled(true);
    properties.getChunking().setOverlapLines(4);
    properties.getChunking().getLine().setMaxLines(20);
    RepoRagChunker chunker = new RepoRagChunker(properties);

    String content =
        "public class Demo {\n"
            + "  /** doc */\n"
            + "  public void first() {\n"
            + "    System.out.println(\"first\");\n"
            + "  }\n\n"
            + "  // region marker\n"
            + "  public void second() {\n"
            + "    System.out.println(\"second\");\n"
            + "  }\n"
            + "}\n";

    ChunkableFile file =
        ChunkableFile.from(Path.of("/tmp/Demo.java"), "Demo.java", "java", content);

    List<Chunk> chunks = chunker.chunk(file);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks.get(0).text()).contains("public class Demo");
    assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.text()).contains("public void second"));
    assertThat(chunks.get(1).overlapLines()).isPositive();
  }

  @Test
  void semanticStrategyFallsBackToLineForPlainText() {
    GitHubRagProperties semanticProps = new GitHubRagProperties();
    semanticProps.getChunking().setStrategy(GitHubRagProperties.Strategy.SEMANTIC);
    semanticProps.getChunking().getSemantic().setEnabled(true);
    RepoRagChunker semanticChunker = new RepoRagChunker(semanticProps);

    GitHubRagProperties lineProps = new GitHubRagProperties();
    lineProps.getChunking().setStrategy(GitHubRagProperties.Strategy.LINE);
    RepoRagChunker lineChunker = new RepoRagChunker(lineProps);

    String content = String.join("\n", List.of("line1", "line2", "line3", "line4"));
    ChunkableFile file =
        ChunkableFile.from(Path.of("/tmp/readme.txt"), "readme.txt", "plain", content);

    List<Chunk> semantic = semanticChunker.chunk(file);
    List<Chunk> linear = lineChunker.chunk(file);

    assertThat(semantic).hasSameSizeAs(linear);
    assertThat(semantic.get(0).text()).isEqualTo(linear.get(0).text());
  }
}
