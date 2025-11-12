package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.RepoRagGenerationService;
import com.aiadvent.mcp.backend.github.rag.RepoRagGenerationService.GenerationResult;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.filter.Filter;

class RepoRagSearchServiceTest {

  @Mock private RepoRagRetrievalPipeline pipeline;
  @Mock private RepoRagSearchReranker reranker;
  @Mock private RepoRagNamespaceStateService namespaceStateService;
  @Mock private RepoRagGenerationService generationService;

  private GitHubRagProperties properties;
  private RepoRagSearchService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    properties = new GitHubRagProperties();
    service =
        new RepoRagSearchService(
            properties, pipeline, reranker, generationService, namespaceStateService);
    when(generationService.generate(any()))
        .thenReturn(new GenerationResult("context", "instructions", false, null, List.of()));
  }

  @Test
  void buildsFilterExpressionWithLanguagesAndRawExpression() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("test").history(List.of()).build();
    Document doc =
        Document.builder()
            .id("1")
            .text("snippet")
            .metadata(Map.of("file_path", "src/Main.java"))
            .score(0.7)
            .build();
    when(pipeline.execute(any())).thenReturn(
        new RepoRagRetrievalPipeline.PipelineResult(query, List.of(doc), List.of("query.compression"), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);

    RepoRagSearchFilters filters = new RepoRagSearchFilters(List.of("java"), List.of());
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw query",
            5,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            filters,
            "repo_owner == 'owner'",
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);
    service.search(command);

    ArgumentCaptor<RepoRagRetrievalPipeline.PipelineInput> captor =
        ArgumentCaptor.forClass(RepoRagRetrievalPipeline.PipelineInput.class);
    verify(pipeline).execute(captor.capture());
    Filter.Expression expression = captor.getValue().filterExpression();
    assertThat(expression).isNotNull();
    assertThat(expression.toString()).contains("namespace").contains("language").contains("repo_owner");
  }

  @Test
  void filtersDocumentsByPathGlobs() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("test").history(List.of()).build();
    Document included =
        Document.builder()
            .id("1")
            .text("ok")
            .metadata(Map.of("file_path", "src/main/java/App.java"))
            .score(0.8)
            .build();
    Document excluded =
        Document.builder()
            .id("2")
            .text("skip")
            .metadata(Map.of("file_path", "docs/readme.md"))
            .score(0.9)
            .build();
    when(pipeline.execute(any())).thenReturn(
        new RepoRagRetrievalPipeline.PipelineResult(query, List.of(included, excluded), List.of(), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(included), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);

    RepoRagSearchFilters filters =
        new RepoRagSearchFilters(List.of(), List.of("src/main/**"));
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw query",
            5,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            filters,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);
    RepoRagSearchService.SearchResponse response = service.search(command);

    assertThat(response.matches()).hasSize(1);
    assertThat(response.matches().get(0).path()).isEqualTo("src/main/java/App.java");
  }

  @Test
  void renderInstructionsReplacesLegacyQueryPlaceholder() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("legacy").history(List.of()).build();
    Document doc =
        Document.builder()
            .id("1")
            .text("snippet")
            .metadata(Map.of("file_path", "src/Main.java"))
            .score(0.7)
            .build();
    when(pipeline.execute(any())).thenReturn(
        new RepoRagRetrievalPipeline.PipelineResult(query, List.of(doc), List.of(), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "legacy",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            "Answer {{query}}");

    RepoRagSearchService.SearchResponse response = service.search(command);
    assertThat(response.instructions()).isEqualTo("Answer legacy");
  }

  @Test
  void generationFallsBackToRawQueryWhenPipelineQueryMissing() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Document doc =
        Document.builder()
            .id("1")
            .text("snippet")
            .metadata(Map.of("file_path", "src/Main.java"))
            .score(0.7)
            .build();
    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        new RepoRagRetrievalPipeline.PipelineResult(null, List.of(doc), List.of(), List.of());
    when(pipeline.execute(any())).thenReturn(pipelineResult);
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "fallback query",
            5,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);

    service.search(command);

    ArgumentCaptor<RepoRagGenerationService.GenerationCommand> captor =
        ArgumentCaptor.forClass(RepoRagGenerationService.GenerationCommand.class);
    verify(generationService).generate(captor.capture());
    assertThat(captor.getValue().query().text()).isEqualTo("fallback query");
  }

  @Test
  void surfacesDetailedMissingPlaceholderError() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("raw").history(List.of()).build();
    Document doc =
        Document.builder()
            .id("1")
            .text("snippet")
            .metadata(Map.of("file_path", "src/Main.java"))
            .score(0.7)
            .build();
    when(pipeline.execute(any())).thenReturn(
        new RepoRagRetrievalPipeline.PipelineResult(query, List.of(doc), List.of(), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);
    when(generationService.generate(any()))
        .thenThrow(
            new IllegalArgumentException(
                "Not all variables were replaced in the template. Missing variable names are: [query]"));

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> service.search(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LLM prompt")
        .hasMessageContaining("query");
  }

  @Test
  void appliesLanguageThresholds() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("test").history(List.of()).build();
    Document weak =
        Document.builder()
            .id("1")
            .text("weak")
            .metadata(Map.of("file_path", "src/Weak.java", "language", "java"))
            .score(0.5)
            .build();
    Document strong =
        Document.builder()
            .id("2")
            .text("strong")
            .metadata(Map.of("file_path", "src/Strong.java", "language", "java"))
            .score(0.9)
            .build();
    when(pipeline.execute(any()))
        .thenReturn(
            new RepoRagRetrievalPipeline.PipelineResult(
                query, List.of(weak, strong), List.of(), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(strong), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw query",
            5,
            null,
            null,
            Map.of("java", 0.8d),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new RepoRagSearchFilters(List.of(), List.of()),
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);

    RepoRagSearchService.SearchResponse response = service.search(command);
    assertThat(response.matches()).hasSize(1);
    assertThat(response.matches().get(0).path()).isEqualTo("src/Strong.java");
  }

  @Test
  void mergesAppliedModulesFromReranker() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("test").history(List.of()).build();
    Document doc =
        Document.builder()
            .id("1")
            .text("snippet")
            .metadata(Map.of("file_path", "src/App.java"))
            .score(0.7)
            .build();
    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        new RepoRagRetrievalPipeline.PipelineResult(query, List.of(doc), List.of("retrieval.multi-query"), List.of(query));
    when(pipeline.execute(any())).thenReturn(pipelineResult);
    RepoRagSearchReranker.PostProcessingResult postProcessingResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), true, List.of("post.neighbor-expand"));
    when(reranker.process(any(), any(), any())).thenReturn(postProcessingResult);

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw query",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);

    RepoRagSearchService.SearchResponse response = service.search(command);
    assertThat(response.appliedModules())
        .containsExactly("retrieval.multi-query", "post.neighbor-expand");
  }

  @Test
  void searchGlobalBuildsFilterAndAnnotatesMatches() {
    Query query = Query.builder().text("global").history(List.of()).build();
    Document javaDoc =
        Document.builder()
            .id("1")
            .text("content")
            .metadata(
                Map.of(
                    "file_path", "repoA/src/App.java",
                    "repo_owner", "repoA",
                    "repo_name", "demo"))
            .score(0.9)
            .build();
    when(pipeline.execute(any()))
        .thenReturn(
            new RepoRagRetrievalPipeline.PipelineResult(
                query, List.of(javaDoc), List.of("retrieval.multi-query"), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult rerankResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(javaDoc), false, List.of());
    when(reranker.process(any(), any(), any())).thenReturn(rerankResult);

    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            "deployment checklist",
            5,
            null,
            null,
            Map.of("java", 0.6),
            null,
            null,
            null,
            null,
            null,
            null,
            new RepoRagSearchFilters(List.of("java"), List.of()),
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null,
            "global-owner",
            "global-mixed");

    RepoRagSearchService.SearchResponse response = service.searchGlobal(command);

    assertThat(response.matches()).hasSize(1);
    assertThat(response.matches().get(0).metadata().get("repo_owner")).isEqualTo("repoA");

    ArgumentCaptor<RepoRagRetrievalPipeline.PipelineInput> captor =
        ArgumentCaptor.forClass(RepoRagRetrievalPipeline.PipelineInput.class);
    verify(pipeline).execute(captor.capture());
    Filter.Expression expression = captor.getValue().filterExpression();
    assertThat(expression).isNotNull();
    assertThat(expression.toString()).contains("language");
  }

  private RepoRagNamespaceStateEntity readyState() {
    RepoRagNamespaceStateEntity entity = new RepoRagNamespaceStateEntity();
    entity.setNamespace("repo:owner/repo");
    entity.setRepoOwner("owner");
    entity.setRepoName("repo");
    entity.setReady(true);
    return entity;
  }
}
