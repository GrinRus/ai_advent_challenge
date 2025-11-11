package com.aiadvent.mcp.backend.github.rag.chunking;

import java.util.List;

public interface ChunkingStrategy {
  List<Chunk> chunk(ChunkingContext context);
}
