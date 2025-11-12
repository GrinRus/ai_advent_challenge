package com.aiadvent.mcp.backend.github.rag.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoRagDocumentMapper {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  public RepoRagDocumentMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Document toDocument(RepoRagDocumentEntity entity) {
    Map<String, Object> metadata = readMetadata(entity.getMetadata());
    putIfAbsent(metadata, "namespace", entity.getNamespace());
    putIfAbsent(metadata, "file_path", entity.getFilePath());
    putIfAbsent(metadata, "chunk_index", entity.getChunkIndex());
    putIfAbsent(metadata, "chunk_hash", entity.getChunkHash());
    putIfAbsent(metadata, "language", entity.getLanguage());
    putIfAbsent(metadata, "summary", entity.getSummary());
    return Document.builder()
        .id(entity.getId() != null ? entity.getId().toString() : null)
        .text(entity.getContent())
        .metadata(metadata)
        .build();
  }

  private Map<String, Object> readMetadata(JsonNode node) {
    if (node == null || node.isNull()) {
      return new LinkedHashMap<>();
    }
    Map<String, Object> converted = objectMapper.convertValue(node, MAP_TYPE);
    return converted != null ? new LinkedHashMap<>(converted) : new LinkedHashMap<>();
  }

  private void putIfAbsent(Map<String, Object> metadata, String key, Object value) {
    if (!metadata.containsKey(key) && value != null) {
      if (value instanceof String stringValue && !StringUtils.hasText(stringValue)) {
        return;
      }
      metadata.put(key, value);
    }
  }
}
