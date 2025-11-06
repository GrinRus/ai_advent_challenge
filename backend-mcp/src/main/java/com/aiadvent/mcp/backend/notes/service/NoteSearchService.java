package com.aiadvent.mcp.backend.notes.service;

import com.aiadvent.mcp.backend.config.NotesBackendProperties;
import com.aiadvent.mcp.backend.notes.persistence.NoteEntity;
import com.aiadvent.mcp.backend.notes.persistence.NoteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class NoteSearchService {

  private final VectorStore vectorStore;
  private final NoteRepository noteRepository;
  private final NotesBackendProperties properties;
  private final ObjectMapper objectMapper;

  public NoteSearchService(
      VectorStore vectorStore,
      NoteRepository noteRepository,
      NotesBackendProperties properties,
      ObjectMapper objectMapper) {
    this.vectorStore = vectorStore;
    this.noteRepository = noteRepository;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public SearchResult search(SearchCommand command) {
    validate(command);

    String namespace = normalize(command.userNamespace());
    String reference = command.userReference().trim();
    int topK = resolveTopK(command.topK());
    double minScore = resolveMinScore(command.minScore());

    FilterExpressionBuilder builder = new FilterExpressionBuilder();
    var expression =
        builder
            .and(builder.eq("user_namespace", namespace), builder.eq("user_reference", reference))
            .build();

    SearchRequest request =
        SearchRequest.builder()
            .query(command.query().trim())
            .topK(topK)
            .similarityThreshold(minScore)
            .filterExpression(expression)
            .build();

    List<Document> documents = vectorStore.similaritySearch(request);
    if (documents.isEmpty()) {
      return new SearchResult(List.of());
    }

    List<UUID> noteIds =
        documents.stream()
            .map(Document::getId)
            .filter(Objects::nonNull)
            .map(UUID::fromString)
            .toList();

    Map<UUID, NoteEntity> notesById =
        noteRepository.findByIdIn(noteIds).stream()
            .collect(Collectors.toMap(NoteEntity::getId, Function.identity()));

    List<NoteMatch> matches = new ArrayList<>();
    for (Document document : documents) {
      String id = document.getId();
      if (!StringUtils.hasText(id)) {
        continue;
      }
      UUID noteId = UUID.fromString(id);
      NoteEntity note = notesById.get(noteId);
      if (note == null) {
        continue;
      }

      Map<String, Object> vectorMetadata =
          document.getMetadata() != null ? document.getMetadata() : Map.of();

      matches.add(
          new NoteMatch(
              noteId,
              note.getTitle(),
              note.getContent(),
              extractTags(note.getTags()),
              safeMetadata(note.getMetadata()),
              note.getCreatedAt(),
              note.getUpdatedAt(),
              document.getScore(),
              vectorMetadata));
    }

    matches.sort(Comparator.comparing(NoteMatch::score, Comparator.nullsLast(Comparator.reverseOrder())));
    return new SearchResult(matches);
  }

  private void validate(SearchCommand command) {
    if (!StringUtils.hasText(command.query())) {
      throw new NotesService.NotesValidationException("Search query must not be blank");
    }
    if (!StringUtils.hasText(command.userNamespace())) {
      throw new NotesService.NotesValidationException("User namespace is required");
    }
    if (!StringUtils.hasText(command.userReference())) {
      throw new NotesService.NotesValidationException("User reference is required");
    }
  }

  private int resolveTopK(Integer value) {
    int defaultTopK = properties.getSearch().getDefaultTopK();
    if (value == null) {
      return defaultTopK;
    }
    int candidate = Math.max(1, value);
    int max = Math.max(defaultTopK, 50);
    return Math.min(candidate, max);
  }

  private double resolveMinScore(Double value) {
    double base = properties.getSearch().getMinScore();
    if (value == null) {
      return base;
    }
    double candidate = Math.max(0.0, value);
    return Math.min(candidate, 0.99);
  }

  private List<String> extractTags(JsonNode tagsNode) {
    if (tagsNode == null || tagsNode.isNull()) {
      return List.of();
    }
    List<String> tags = new ArrayList<>();
    if (tagsNode instanceof ArrayNode arrayNode) {
      arrayNode.elements().forEachRemaining(node -> tags.add(node.asText()));
    }
    return tags;
  }

  private JsonNode safeMetadata(JsonNode node) {
    if (node == null) {
      return objectMapper.createObjectNode();
    }
    return node;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new NotesService.NotesValidationException("Value must not be blank");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  public record SearchCommand(
      String query, String userNamespace, String userReference, Integer topK, Double minScore) {}

  public record SearchResult(List<NoteMatch> matches) {}

  public record NoteMatch(
      UUID noteId,
      String title,
      String content,
      List<String> tags,
      JsonNode metadata,
      java.time.Instant createdAt,
      java.time.Instant updatedAt,
      Double score,
      Map<String, Object> vectorMetadata) {}
}
