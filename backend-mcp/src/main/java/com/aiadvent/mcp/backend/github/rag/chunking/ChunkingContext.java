package com.aiadvent.mcp.backend.github.rag.chunking;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;

public record ChunkingContext(
    ChunkableFile file,
    GitHubRagProperties.Chunking config,
    GitHubRagProperties.Strategy strategy) {}
