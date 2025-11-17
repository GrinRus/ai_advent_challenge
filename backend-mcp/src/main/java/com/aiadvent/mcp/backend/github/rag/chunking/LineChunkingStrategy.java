package com.aiadvent.mcp.backend.github.rag.chunking;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.CollectionUtils;

public class LineChunkingStrategy implements ChunkingStrategy {

  @Override
  public List<Chunk> chunk(ChunkingContext context) {
    List<String> lines = context.file().lines();
    if (CollectionUtils.isEmpty(lines)) {
      return List.of();
    }
    Parameters parameters = parameters(context);
    List<Chunk> chunks = new ArrayList<>();
    List<String> buffer = new ArrayList<>();
    int chunkStartLine = 1;
    int bytesBudget = 0;
    int overlapFromPrevious = 0;

    for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
      String line = lines.get(lineNumber - 1);
      int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
      int newlineBytes = buffer.isEmpty() ? 0 : 1;
      boolean wouldExceedLines = buffer.size() >= parameters.maxLines;
      boolean wouldExceedBytes = !buffer.isEmpty() && (bytesBudget + lineBytes + newlineBytes > parameters.maxBytes);
      if (wouldExceedLines || wouldExceedBytes) {
        overlapFromPrevious = appendChunk(chunks, buffer, chunkStartLine, context, overlapFromPrevious);
        int preserved = Math.min(parameters.overlapLines, buffer.size());
        buffer = new ArrayList<>(buffer.subList(Math.max(buffer.size() - preserved, 0), buffer.size()));
        chunkStartLine = lineNumber - preserved;
        bytesBudget = recomputeBytes(buffer);
        overlapFromPrevious = preserved;
      }
      if (!buffer.isEmpty()) {
        bytesBudget += 1;
      }
      buffer.add(line);
      bytesBudget += lineBytes;
    }
    appendChunk(chunks, buffer, chunkStartLine, context, overlapFromPrevious);
    return chunks;
  }

  private Parameters parameters(ChunkingContext context) {
    GitHubRagProperties.Chunking chunking = context.config();
    if (context.strategy() == GitHubRagProperties.Strategy.BYTE) {
      return new Parameters(Integer.MAX_VALUE, Math.max(512, chunking.getByteStrategy().getMaxBytes()), chunking.getOverlapLines());
    }
    return new Parameters(
        Math.max(1, chunking.getLine().getMaxLines()),
        Math.max(256, chunking.getLine().getMaxBytes()),
        Math.max(0, chunking.getOverlapLines()));
  }

  private int recomputeBytes(List<String> lines) {
    if (lines.isEmpty()) {
      return 0;
    }
    int total = 0;
    for (int i = 0; i < lines.size(); i++) {
      total += lines.get(i).getBytes(StandardCharsets.UTF_8).length;
      if (i < lines.size() - 1) {
        total += 1;
      }
    }
    return total;
  }

  private int appendChunk(
      List<Chunk> target,
      List<String> buffer,
      int chunkStartLine,
      ChunkingContext context,
      int overlapFromPrevious) {
    if (buffer.isEmpty()) {
      return overlapFromPrevious;
    }
    String text = String.join("\n", buffer);
    int chunkEnd = chunkStartLine + buffer.size() - 1;
    AstSymbolMetadata astMetadata =
        context.file().astContext().flatMap(ctx -> ctx.symbolForRange(chunkStartLine, chunkEnd)).orElse(null);
    Chunk chunk =
        Chunk.from(
            text,
            chunkStartLine,
            chunkEnd,
            context.file().language(),
            context.file().parentSymbolResolver(),
            overlapFromPrevious,
            astMetadata);
    if (chunk != null) {
      target.add(chunk);
    }
    return 0;
  }

  private record Parameters(int maxLines, int maxBytes, int overlapLines) {}
}
