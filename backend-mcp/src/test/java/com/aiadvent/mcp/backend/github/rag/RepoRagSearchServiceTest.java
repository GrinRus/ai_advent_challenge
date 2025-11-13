package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        .thenReturn(
            new RepoRagGenerationService.GenerationResult(
                "ctx", "instr", false, null, List.of(), "summary", "raw"));
  }

  @Test
  void searchUsesResolvedPlan() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState()));
    Query query = Query.builder().text("raw").history(List.of()).build();
    Document doc =
        Document.builder().id("1").text("snippet").metadata(Map.of("language", "java")).score(0.8).build();
    when(pipeline.execute(any()))
        .thenReturn(
            new RepoRagRetrievalPipeline.PipelineResult(query, List.of(doc), List.of("multi-query"), List.of(query)));
    RepoRagSearchReranker.PostProcessingResult postResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), false, List.of("post"));
    when(reranker.process(any(), any(), any())).thenReturn(postResult);

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw",
            plan("balanced"),
            List.of(),
            null,
            RepoRagResponseChannel.BOTH);

    RepoRagSearchService.SearchResponse response = service.search(command);

    ArgumentCaptor<RepoRagRetrievalPipeline.PipelineInput> captor =
        ArgumentCaptor.forClass(RepoRagRetrievalPipeline.PipelineInput.class);
    verify(pipeline).execute(captor.capture());
    assertThat(captor.getValue().topK()).isEqualTo(8);
    assertThat(response.appliedModules()).contains("profile:balanced");
  }

  @Test
  void searchFailsWhenNamespaceMissing() {
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.empty());

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "raw",
            plan("balanced"),
            List.of(),
            null,
            RepoRagResponseChannel.BOTH);

    assertThatThrownBy(() -> service.search(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has not been indexed");
  }

  @Test
  void globalSearchRequiresRawQuery() {
    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            "  ",
            plan("balanced"),
            List.of(),
            null,
            "global",
            "global",
            RepoRagResponseChannel.BOTH);

    assertThatThrownBy(() -> service.searchGlobal(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rawQuery");
  }

  private RepoRagNamespaceStateEntity readyState() {
    RepoRagNamespaceStateEntity entity = new RepoRagNamespaceStateEntity();
    entity.setNamespace("repo:owner/repo");
    entity.setRepoOwner("owner");
    entity.setRepoName("repo");
    entity.setReady(true);
    return entity;
  }

  private RagParameterGuard.ResolvedSearchPlan plan(String name) {
    return new RagParameterGuard.ResolvedSearchPlan(
        name,
        8,
        8,
        0.55d,
        Map.of("java", 0.5d),
        8,
        true,
        2.0d,
        new RepoRagMultiQueryOptions(true, 3, 3),
        new RagParameterGuard.NeighborOptions("LINEAR", 1, 4),
        RagParameterGuard.SearchPlanMode.STANDARD,
        null,
        null,
        List.of());
  }
}
