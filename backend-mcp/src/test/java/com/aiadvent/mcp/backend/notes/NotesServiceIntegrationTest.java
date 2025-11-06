package com.aiadvent.mcp.backend.notes;

import com.aiadvent.mcp.backend.McpApplication;
import com.aiadvent.mcp.backend.notes.service.NoteSearchService;
import com.aiadvent.mcp.backend.notes.service.NoteSearchService.SearchCommand;
import com.aiadvent.mcp.backend.notes.service.NotesService;
import com.aiadvent.mcp.backend.notes.service.NotesService.SaveNoteCommand;
import com.aiadvent.mcp.backend.notes.tool.NotesTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
    classes = {McpApplication.class, NotesServiceIntegrationTest.TestNotesConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("notes")
class NotesServiceIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg15").withReuse(true);

  @DynamicPropertySource
  static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.liquibase.change-log",
        () -> "classpath:db/changelog/notes/db.changelog-master.yaml");
    registry.add("spring.liquibase.contexts", () -> "notes");
    registry.add("notes.storage.vector-table", () -> "note_vector_store");
    registry.add("notes.embedding.model", () -> "text-embedding-3-small");
    registry.add("notes.embedding.dimensions", () -> 1536);
  }

  @Autowired private NotesService notesService;

  @Autowired private NoteSearchService noteSearchService;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private NotesTools notesTools;

  @Test
  void saveNoteIsIdempotentAndSearchable() {
    SaveNoteCommand command =
        new SaveNoteCommand(
            "Meeting notes",
            "Discuss roadmap for Wave 28 and define MCP responsibilities.",
            List.of("planning", "wave28"),
            objectMapper.createObjectNode().put("language", "ru"),
            "telegram",
            "123456",
            "telegram");

    var first = notesService.saveNote(command);
    assertThat(first.created()).isTrue();
    assertThat(first.embeddingProvider()).isEqualTo("text-embedding-3-small");
    assertThat(first.embeddingDimensions()).isEqualTo(1536);

    var second = notesService.saveNote(command);
    assertThat(second.created()).isFalse();

    var searchResult =
        noteSearchService.search(
            new SearchCommand(
                "Wave 28 roadmap", "telegram", "123456", /* topK= */ 5, /* minScore= */ 0.40));

    assertThat(searchResult.matches()).hasSize(1);
    var match = searchResult.matches().get(0);
    assertThat(match.noteId()).isEqualTo(first.noteId());
    assertThat(match.tags()).containsExactly("planning", "wave28");
    assertThat(match.metadata().get("language").asText()).isEqualTo("ru");
    assertThat(match.score()).isNotNull();
  }

  @Test
  void toolContractsExposeSaveAndSearchOperations() {
    var input =
        new NotesTools.SaveNoteInput(
            "Tool note",
            "Use tools to persist and retrieve vectors.",
            List.of("tools", "notes"),
            Map.of("source", "tool"),
            "telegram",
            "tool-user",
            "telegram");

    var saveResponse = notesTools.saveNote(input);
    assertThat(saveResponse.noteId()).isNotNull();
    assertThat(saveResponse.created()).isTrue();

    var searchResponse =
        notesTools.searchSimilar(
            new NotesTools.SearchSimilarInput("persist vectors", "telegram", "tool-user", 5, 0.40));

    assertThat(searchResponse.matches()).hasSize(1);
    var match = searchResponse.matches().get(0);
    assertThat(match.noteId()).isEqualTo(saveResponse.noteId());
    assertThat(match.tags()).containsExactlyInAnyOrder("tools", "notes");
    assertThat(match.vectorMetadata()).containsEntry("user_namespace", "telegram");
  }

  @TestConfiguration
  static class TestNotesConfiguration {

    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new DeterministicEmbeddingModel(1536);
    }
  }

  static class DeterministicEmbeddingModel implements EmbeddingModel {

    private final int dimensions;
    private final AtomicInteger indexCounter = new AtomicInteger();

    DeterministicEmbeddingModel(int dimensions) {
      this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest embeddingRequest) {
      List<String> inputs = embeddingRequest.getInstructions();
      List<Embedding> embeddings = new ArrayList<>(inputs.size());
      for (String text : inputs) {
        embeddings.add(
            new Embedding(vectorFor(text), indexCounter.getAndIncrement(), /* metadata= */ null));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      String text = document.getText() != null ? document.getText() : "";
      return vectorFor(text);
    }

    private float[] vectorFor(String text) {
      float base =
          (float)
              ((Math.abs(text.hashCode()) % 1000) + 1)
                  / 1000.0f; // deterministic but non-zero magnitude
      float[] vector = new float[this.dimensions];
      Arrays.fill(vector, base);
      return vector;
    }

    @Override
    public int dimensions() {
      return this.dimensions;
    }
  }
}
