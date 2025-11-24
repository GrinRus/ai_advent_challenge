package com.aiadvent.mcp.backend.github.rag.chunking;

import java.util.List;
import java.util.Set;

public record AstSymbolMetadata(
    String symbolFqn,
    String symbolKind,
    String symbolVisibility,
    String symbolSignature,
    String docstring,
    boolean test,
    List<String> imports,
    List<String> callsOut,
    List<String> callsIn,
    List<String> implementsTypes,
    List<String> readsFields,
    Set<String> usesTypes,
    int lineStart,
    int lineEnd) {}
