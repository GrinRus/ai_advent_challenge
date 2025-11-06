package com.aiadvent.mcp.backend.notes.tool;

import com.aiadvent.mcp.backend.notes.service.NoteSearchService;
import com.aiadvent.mcp.backend.notes.service.NoteSearchService.NoteMatch;
import com.aiadvent.mcp.backend.notes.service.NoteSearchService.SearchCommand;
import com.aiadvent.mcp.backend.notes.service.NoteSearchService.SearchResult;
import com.aiadvent.mcp.backend.notes.service.NotesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NotesTools {

  private final NotesService notesService;
  private final NoteSearchService searchService;
  private final ObjectMapper objectMapper;

  public NotesTools(
      NotesService notesService, NoteSearchService searchService, ObjectMapper objectMapper) {
    this.notesService = notesService;
    this.searchService = searchService;
    this.objectMapper = objectMapper;
  }

  @Tool(
      name = "notes.save_note",
      description =
          "Сохраняет заметку пользователя и индексирует её в PgVector. Обязательные поля: "
              + "`title`, `content`, `userNamespace`, `userReference`.")
  public SaveNoteResponse saveNote(SaveNoteInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Input must not be null");
    }

    JsonNode metadataNode =
        input.metadata() != null
            ? objectMapper.valueToTree(input.metadata())
            : objectMapper.createObjectNode();

    NotesService.SaveNoteResult result =
        notesService.saveNote(
            new NotesService.SaveNoteCommand(
                input.title(),
                input.content(),
                input.tags(),
                metadataNode,
                input.userNamespace(),
                input.userReference(),
                input.sourceChannel()));

    return new SaveNoteResponse(
        result.noteId(),
        result.createdAt(),
        result.updatedAt(),
        result.created(),
        result.embeddingProvider(),
        result.embeddingDimensions());
  }

  @Tool(
      name = "notes.search_similar",
      description =
          "Выполняет поиск похожих заметок для пользователя по векторному хранилищу. "
              + "Обязательные поля: `query`, `userNamespace`, `userReference`.")
  public SearchNotesResponse searchSimilar(SearchSimilarInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Input must not be null");
    }
    SearchResult searchResult =
        searchService.search(
            new SearchCommand(
                input.query(),
                input.userNamespace(),
                input.userReference(),
                input.topK(),
                input.minScore()));

    List<NoteMatchView> matches =
        searchResult.matches().stream().map(this::mapMatch).toList();

    return new SearchNotesResponse(matches);
  }

  private NoteMatchView mapMatch(NoteMatch match) {
    JsonNode metadata = match.metadata() != null ? match.metadata() : objectMapper.createObjectNode();
    Map<String, Object> vectorMetadata =
        match.vectorMetadata() != null ? match.vectorMetadata() : Collections.emptyMap();

    return new NoteMatchView(
        match.noteId(),
        match.title(),
        match.content(),
        match.tags(),
        metadata,
        match.createdAt(),
        match.updatedAt(),
        match.score(),
        vectorMetadata);
  }

  public record SaveNoteInput(
      String title,
      String content,
      List<String> tags,
      Map<String, Object> metadata,
      String userNamespace,
      String userReference,
      String sourceChannel) {

    public SaveNoteInput {
      if (!StringUtils.hasText(sourceChannel)) {
        sourceChannel = "unknown";
      }
    }
  }

  public record SaveNoteResponse(
      UUID noteId,
      Instant createdAt,
      Instant updatedAt,
      boolean created,
      String embeddingProvider,
      int embeddingDimensions) {}

  public record SearchSimilarInput(
      String query, String userNamespace, String userReference, Integer topK, Double minScore) {}

  public record NoteMatchView(
      UUID noteId,
      String title,
      String content,
      List<String> tags,
      JsonNode metadata,
      Instant createdAt,
      Instant updatedAt,
      Double score,
      Map<String, Object> vectorMetadata) {}

  public record SearchNotesResponse(List<NoteMatchView> matches) {}
}
