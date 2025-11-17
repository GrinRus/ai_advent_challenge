package com.aiadvent.mcp.backend.github.rag.chunking;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

public class TokenChunkingStrategy implements ChunkingStrategy {

  private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();
  private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

  @Override
  public List<Chunk> chunk(ChunkingContext context) {
    String content = context.file().content();
    if (!StringUtils.hasText(content)) {
      return List.of();
    }
    TokenParameters params = parameters(context);
    List<Integer> tokens = ENCODING.encode(content).boxed();
    if (tokens.isEmpty()) {
      return List.of();
    }
    int overlapTokens = Math.min(params.overlapTokens(), Math.max(0, params.chunkSize() - 1));
    int step = Math.max(1, params.chunkSize() - overlapTokens);
    List<Chunk> chunks = new ArrayList<>();
    int produced = 0;
    int start = 0;
    int lastStartIndex = -1;
    int previousEndLine = 0;

    while (start < tokens.size() && produced < params.maxChunks()) {
      int end = Math.min(tokens.size(), start + params.chunkSize());
      List<Integer> slice = tokens.subList(start, end);
      String chunkText = decode(slice).trim();
      if (chunkText.length() >= params.minChars()) {
        int charStart = findNextOccurrence(content, chunkText, lastStartIndex + 1);
        if (charStart < 0) {
          break;
        }
        int charEnd = Math.min(content.length(), charStart + chunkText.length());
        LineIndex.LineRange range = context.file().lineIndex().rangeForSpan(charStart, charEnd);
        int overlapLines = previousEndLine > 0 ? Math.max(0, previousEndLine - range.start() + 1) : 0;
        AstSymbolMetadata astMetadata =
            context.file().astContext().flatMap(ctx -> ctx.symbolForRange(range.start(), range.end())).orElse(null);
        Chunk chunk =
            Chunk.from(
                chunkText,
                range.start(),
                range.end(),
                context.file().language(),
                context.file().parentSymbolResolver(),
                overlapLines,
                astMetadata);
        if (chunk != null) {
          chunks.add(chunk);
          produced++;
          lastStartIndex = charStart;
          previousEndLine = range.end();
        }
      }
      if (end >= tokens.size()) {
        break;
      }
      start += step;
    }
    return chunks;
  }

  private TokenParameters parameters(ChunkingContext context) {
    GitHubRagProperties.Chunking chunking = context.config();
    int minChars = Math.max(chunking.getToken().getMinChunkChars(), chunking.getToken().getMinChunkLengthToEmbed());
    if (context.strategy() == GitHubRagProperties.Strategy.SEMANTIC) {
      if (!chunking.getSemantic().isEnabled()) {
        return new TokenParameters(1, 0, 0, 1);
      }
      return new TokenParameters(
          Math.max(1, chunking.getSemantic().getChunkSizeTokens()),
          chunking.getOverlapTokens(),
          minChars,
          chunking.getToken().getMaxNumChunks());
    }
    return new TokenParameters(
        Math.max(1, chunking.getToken().getChunkSizeTokens()),
        chunking.getOverlapTokens(),
        minChars,
        chunking.getToken().getMaxNumChunks());
  }

  private int findNextOccurrence(String content, String chunkText, int fromIndex) {
    if (!StringUtils.hasText(chunkText)) {
      return -1;
    }
    return content.indexOf(chunkText, Math.max(0, fromIndex));
  }

  private String decode(List<Integer> tokens) {
    if (tokens.isEmpty()) {
      return "";
    }
    IntArrayList list = new IntArrayList(tokens.size());
    tokens.forEach(list::add);
    return ENCODING.decode(list);
  }

  private record TokenParameters(int chunkSize, int overlapTokens, int minChars, int maxChunks) {}
}
