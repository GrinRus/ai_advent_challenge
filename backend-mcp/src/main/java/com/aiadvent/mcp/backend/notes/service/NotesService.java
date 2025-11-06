package com.aiadvent.mcp.backend.notes.service;

import com.aiadvent.mcp.backend.config.NotesBackendProperties;
import com.aiadvent.mcp.backend.notes.persistence.NoteEntity;
import com.aiadvent.mcp.backend.notes.persistence.NoteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
@Transactional
public class NotesService {

  private static final String HASH_ALGORITHM = "SHA-256";

  private final NoteRepository noteRepository;
  private final VectorStore vectorStore;
  private final NotesBackendProperties properties;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;
  private final String vectorTableName;

  public NotesService(
      NoteRepository noteRepository,
      VectorStore vectorStore,
      NotesBackendProperties properties,
      ObjectMapper objectMapper,
      JdbcTemplate jdbcTemplate) {
    this.noteRepository = noteRepository;
    this.vectorStore = vectorStore;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.vectorTableName = validateTableName(properties.getStorage().getVectorTable());
  }

  public SaveNoteResult saveNote(SaveNoteCommand command) {
    validateCommand(command);

    String normalizedNamespace = normalize(command.userNamespace());
    String normalizedReference = command.userReference().trim();
    List<String> normalizedTags = normalizeTags(command.tags());
    JsonNode tagsNode = toArrayNode(normalizedTags);
    JsonNode metadataNode =
        command.metadata() != null ? command.metadata() : NullNode.getInstance();

    String contentHash = hashContent(command.content());

    Optional<NoteEntity> existing =
        noteRepository.findByUserNamespaceAndUserReferenceAndContentHash(
            normalizedNamespace, normalizedReference, contentHash);

    NoteEntity entity =
        existing.orElseGet(NoteEntity::new);

    boolean created = existing.isEmpty();
    entity.setTitle(command.title().trim());
    entity.setContent(command.content().trim());
    entity.setTags(tagsNode);
    entity.setMetadata(metadataNode);
    entity.setSourceChannel(command.sourceChannel());
    entity.setContentHash(contentHash);
    entity.setUserNamespace(normalizedNamespace);
    entity.setUserReference(normalizedReference);

    NoteEntity saved = noteRepository.saveAndFlush(entity);

    Document vectorDocument = buildDocument(saved, normalizedTags);

    try {
      if (!created) {
        vectorStore.delete(List.of(saved.getId().toString()));
      }
      vectorStore.add(List.of(vectorDocument));
      updateVectorMetadata(saved.getId());
    } catch (RuntimeException ex) {
      throw new NotesStorageException("Failed to persist note embedding", ex);
    }

    return new SaveNoteResult(
        saved.getId(),
        saved.getCreatedAt(),
        saved.getUpdatedAt(),
        created,
        properties.getEmbedding().getModel(),
        properties.getEmbedding().getDimensions());
  }

  private void updateVectorMetadata(UUID noteId) {
    jdbcTemplate.update(
        "UPDATE " + vectorTableName + " SET embedding_provider = ?, embedding_dimensions = ? WHERE id = ?",
        properties.getEmbedding().getModel(),
        properties.getEmbedding().getDimensions(),
        noteId);
  }

  private Document buildDocument(NoteEntity note, List<String> tags) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("note_id", note.getId().toString());
    metadata.put("user_namespace", note.getUserNamespace());
    metadata.put("user_reference", note.getUserReference());
    metadata.put("title", note.getTitle());
    metadata.put("tags", tags);
    metadata.put("source_channel", note.getSourceChannel());
    metadata.put("created_at", toIsoString(note.getCreatedAt()));
    metadata.put("updated_at", toIsoString(note.getUpdatedAt()));
    metadata.put("embedding_provider", properties.getEmbedding().getModel());

    StringBuilder formatted = new StringBuilder();
    if (StringUtils.hasText(note.getTitle())) {
      formatted.append(note.getTitle().trim()).append("\n\n");
    }
    formatted.append(note.getContent());

    return Document.builder()
        .id(note.getId().toString())
        .text(formatted.toString())
        .metadata(metadata)
        .build();
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new NotesValidationException("Value must not be blank");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private List<String> normalizeTags(List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return List.of();
    }
    Set<String> unique =
        tags.stream()
            .filter(StringUtils::hasText)
            .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (unique.size() > properties.getValidation().getMaxTags()) {
      throw new NotesValidationException(
          "Tags limit exceeded (" + properties.getValidation().getMaxTags() + ")");
    }
    return new ArrayList<>(unique);
  }

  private JsonNode toArrayNode(List<String> values) {
    ArrayNode array = objectMapper.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  private void validateCommand(SaveNoteCommand command) {
    if (!StringUtils.hasText(command.title())) {
      throw new NotesValidationException("Title must not be blank");
    }
    if (!StringUtils.hasText(command.content())) {
      throw new NotesValidationException("Content must not be blank");
    }
    if (!StringUtils.hasText(command.userNamespace())) {
      throw new NotesValidationException("User namespace is required");
    }
    if (!StringUtils.hasText(command.userReference())) {
      throw new NotesValidationException("User reference is required");
    }
    if (command.title().length() > properties.getValidation().getMaxTitleLength()) {
      throw new NotesValidationException("Title length exceeds allowed limit");
    }
    if (command.content().length() > properties.getValidation().getMaxContentLength()) {
      throw new NotesValidationException("Content length exceeds allowed limit");
    }
  }

  private String hashContent(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
      byte[] bytes = digest.digest(content.trim().getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new NotesStorageException("Unable to compute note hash", ex);
    }
  }

  private String toIsoString(Instant instant) {
    return instant != null ? instant.toString() : null;
  }

  private String validateTableName(String value) {
    String normalized = value != null ? value.trim() : "";
    if (!normalized.matches("[a-zA-Z0-9_]+")) {
      throw new IllegalArgumentException("Invalid vector table name: " + value);
    }
    return normalized;
  }

  public record SaveNoteCommand(
      String title,
      String content,
      List<String> tags,
      JsonNode metadata,
      String userNamespace,
      String userReference,
      String sourceChannel) {}

  public record SaveNoteResult(
      UUID noteId,
      Instant createdAt,
      Instant updatedAt,
      boolean created,
      String embeddingProvider,
      int embeddingDimensions) {}

  public static class NotesStorageException extends RuntimeException {
    public NotesStorageException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class NotesValidationException extends RuntimeException {
    public NotesValidationException(String message) {
      super(message);
    }
  }
}
