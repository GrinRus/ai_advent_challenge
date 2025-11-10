package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
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

  private GitHubRagProperties properties;
  private RepoRagSearchService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    properties = new GitHubRagProperties();
    service = new RepoRagSearchService(properties, pipeline, reranker, namespaceStateService);
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
            filters,
            "repo_owner == 'owner'",
            List.of(),
            null,
            Boolean.TRUE,
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
            filters,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null);
    RepoRagSearchService.SearchResponse response = service.search(command);

    assertThat(response.matches()).hasSize(1);
    assertThat(response.matches().get(0).path()).isEqualTo("src/main/java/App.java");
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

