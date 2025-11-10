package com.aiadvent.mcp.backend.github.rag.postprocessing;

public record RepoRagPostProcessingRequest(
    int maxContextTokens, String locale, int maxSnippetLines, boolean compressionEnabled) {}

