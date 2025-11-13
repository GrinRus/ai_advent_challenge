package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Integration-style test that wires the real RepoRagSearchService with the Generation layer
 * to ensure both response channels (raw + summary) are produced end-to-end.
 */
class RepoRagSearchIntegrationTest {

  private GitHubRagProperties properties;
  private RepoRagRetrievalPipeline retrievalPipeline;
  private RepoRagSearchReranker reranker;
  private RepoRagNamespaceStateService namespaceStateService;
  private RepoRagSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    properties = new GitHubRagProperties();
    properties.setParameterProfiles(List.of(profile("balanced")));
    properties.setDefaultProfile("balanced");
    properties.afterPropertiesSet();

    retrievalPipeline = Mockito.mock(RepoRagRetrievalPipeline.class);
    reranker = Mockito.mock(RepoRagSearchReranker.class);
    namespaceStateService = Mockito.mock(RepoRagNamespaceStateService.class);

    RepoRagGenerationService generationService =
        new RepoRagGenerationService(properties, new DefaultResourceLoader());
    service =
        new RepoRagSearchService(
            properties, retrievalPipeline, reranker, generationService, namespaceStateService);
  }

  @Test
  void searchProducesSeparateRawAndSummaryChannels() {
    // Namespace preresolved/ready
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setNamespace("repo:owner/repo");
    state.setRepoOwner("owner");
    state.setRepoName("repo");
    state.setReady(true);
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(state));

    // Retrieval pipeline returns a single document chunk
    Query builtQuery = Query.builder().text("class FooService").build();
    Document document =
        Document.builder()
            .id("1")
            .text("public class FooService {}")
            .metadata(Map.of("file_path", "src/main/java/FooService.java"))
            .score(0.92)
            .build();
    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        new RepoRagRetrievalPipeline.PipelineResult(
            builtQuery,
            List.of(document),
            List.of("retrieval.multi-query"),
            List.of(builtQuery));
    when(retrievalPipeline.buildQuery(any(), anyList(), any(), anyInt())).thenAnswer(inv -> builtQuery);
    when(retrievalPipeline.execute(any())).thenReturn(pipelineResult);

    // Reranker keeps the same document
    when(reranker.process(any(), anyList(), any()))
        .thenAnswer(
            inv ->
                new RepoRagSearchReranker.PostProcessingResult(
                    inv.getArgument(1), false, List.of("post.heuristic-rerank")));

    RagParameterGuard guard = new RagParameterGuard(properties);
    GitHubRagProperties.ResolvedRagParameterProfile profile = properties.resolveProfile("balanced");
    RagParameterGuard.ResolvedSearchPlan plan = guard.apply(profile).plan();

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "repo",
            "class FooService",
            plan,
            List.of(),
            null,
            RepoRagResponseChannel.BOTH);

    RepoRagSearchService.SearchResponse response = service.search(command);

    assertThat(response.matches()).hasSize(1);
    assertThat(response.augmentedPrompt()).contains("Ты выступаешь экспертом");
    assertThat(response.instructions()).contains("Ты делаешь краткое резюме");
    assertThat(response.rawAnswer()).isEqualTo(response.augmentedPrompt());
    assertThat(response.summary()).isEqualTo(response.instructions());
    assertThat(response.appliedModules())
        .contains("generation.contextual-augmenter", "generation.summary", "profile:balanced");
  }

  private static GitHubRagProperties.RagParameterProfile profile(String name) {
    GitHubRagProperties.RagParameterProfile profile = new GitHubRagProperties.RagParameterProfile();
    profile.setName(name);
    profile.setTopK(8);
    profile.setTopKPerQuery(8);
    profile.setMinScore(0.55d);
    profile.setRerankTopN(8);
    profile.setCodeAwareEnabled(true);
    profile.setCodeAwareHeadMultiplier(2.0d);
    profile.getMultiQuery().setEnabled(true);
    profile.getMultiQuery().setQueries(3);
    profile.getMultiQuery().setMaxQueries(3);
    profile.getNeighbor().setStrategy("LINEAR");
    profile.getNeighbor().setRadius(1);
    profile.getNeighbor().setLimit(4);
    profile.setMinScoreFallback(0.45d);
    profile.setMinScoreClassifier("overview");
    return profile;
  }
}
