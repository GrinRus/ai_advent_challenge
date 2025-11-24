package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.RagParameterGuard;
import com.aiadvent.mcp.backend.github.rag.RepoRagGenerationService;
import com.aiadvent.mcp.backend.github.rag.RepoRagNamespaceStateService;
import com.aiadvent.mcp.backend.github.rag.RepoRagResponseChannel;
import com.aiadvent.mcp.backend.github.rag.RepoRagRetrievalPipeline;
import com.aiadvent.mcp.backend.github.rag.RepoRagSearchReranker;
import com.aiadvent.mcp.backend.github.rag.RepoRagSearchService;
import com.aiadvent.mcp.backend.github.rag.SymbolGraphWriter;
import com.aiadvent.mcp.backend.github.rag.ast.AstFileContextFactory;
import com.aiadvent.mcp.backend.github.rag.ast.AstTestSupport;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentMapper;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

class RepoRagCallGraphE2ETest {

  private static final String NAMESPACE = "repo:owner/demo";
  private static final String REPO_OWNER = "owner";
  private static final String REPO_NAME = "demo";
  private static final String WORKSPACE_ID = "workspace-callgraph";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private Path workspaceDir;

  @BeforeEach
  void setUpWorkspace() throws IOException {
    Path fixtures =
        Path.of("src", "test", "resources", "mini-repos", "java")
            .toAbsolutePath()
            .normalize();
    workspaceDir = Files.createTempDirectory("repo-call-graph");
    copyDirectory(fixtures, workspaceDir);
  }

  @Test
  void fetchIndexAndSearchReturnsCallGraphNeighbors() throws IOException {
    TempWorkspaceService workspaceService = mock(TempWorkspaceService.class);
    Workspace workspace =
        new Workspace(
            WORKSPACE_ID,
            workspaceDir,
            Instant.now(),
            Instant.now().plusSeconds(600),
            "req-e2e",
            REPO_OWNER + "/" + REPO_NAME,
            "refs/heads/main",
            0L,
            null,
            List.of(),
            null,
            null,
            null);
    when(workspaceService.findWorkspace(WORKSPACE_ID)).thenReturn(Optional.of(workspace));

    RepoRagVectorStoreAdapter vectorStoreAdapter = mock(RepoRagVectorStoreAdapter.class);
    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(new HashSet<>());
    List<Document> indexedDocuments = new ArrayList<>();
    doAnswer(
            (Answer<Void>)
                invocation -> {
                  @SuppressWarnings("unchecked")
                  List<Document> docs = invocation.getArgument(2);
                  indexedDocuments.addAll(docs);
                  return null;
                })
        .when(vectorStoreAdapter)
        .replaceFile(anyString(), anyString(), anyList());

    RepoRagFileStateRepository fileStateRepository = mock(RepoRagFileStateRepository.class);
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(fileStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RepoRagSymbolGraphRepository symbolGraphRepository = mock(RepoRagSymbolGraphRepository.class);
    List<RepoRagSymbolGraphEntity> capturedEdges = new ArrayList<>();
    when(symbolGraphRepository.saveAll(anyList()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<RepoRagSymbolGraphEntity> edges = invocation.getArgument(0);
              capturedEdges.addAll(edges);
              return edges;
            });

    GitHubRagProperties indexProperties = new GitHubRagProperties();
    indexProperties.getChunking().getLine().setMaxLines(4);
    indexProperties.getChunking().setOverlapLines(0);
    indexProperties.getAst().setEnabled(true);
    indexProperties.getAst().setNativeEnabled(false);
    indexProperties.getAst().setLanguages(List.of("java"));

    RepoRagChunker chunker = new RepoRagChunker(indexProperties);
    AstFileContextFactory astFactory = AstTestSupport.astComponents(indexProperties).factory();

    RepoRagIndexService indexService =
        new RepoRagIndexService(
            workspaceService,
            vectorStoreAdapter,
            fileStateRepository,
            chunker,
            indexProperties,
            astFactory,
            new SymbolGraphWriter(symbolGraphRepository, null),
            null);

    RepoRagIndexService.IndexRequest indexRequest =
        new RepoRagIndexService.IndexRequest(
            REPO_OWNER,
            REPO_NAME,
            WORKSPACE_ID,
            NAMESPACE,
            "refs/heads/main",
            null,
            0L,
            Instant.now());

    RepoRagIndexService.IndexResult result = indexService.indexWorkspace(indexRequest);
    assertThat(result.astReady()).isTrue();
    assertThat(indexedDocuments).isNotEmpty();
    assertThat(capturedEdges).isNotEmpty();
    Document anchor = indexedDocuments.get(0);
    Map<String, Object> neighborMetadata = new HashMap<>(anchor.getMetadata());
    neighborMetadata.put("neighborOfSpanHash", anchor.getMetadata().get("span_hash"));
    neighborMetadata.put("neighbor_relation", "CALLS");
    neighborMetadata.put("neighbor_symbol", neighborMetadata.get("symbol_fqn"));
    neighborMetadata.put("neighbor_referenced_symbol", neighborMetadata.get("symbol_fqn"));
    neighborMetadata.entrySet().removeIf(entry -> entry.getValue() == null);
    Document neighbor =
        Document.builder()
            .id(((String) neighborMetadata.get("chunk_hash")) + "-neighbor")
            .text(anchor.getText())
            .metadata(neighborMetadata)
            .build();
    indexedDocuments.add(neighbor);

    List<RepoRagDocumentEntity> storedEntities = convertDocuments(indexedDocuments);

    RepoRagDocumentRepository documentRepository = mock(RepoRagDocumentRepository.class);
    when(documentRepository.findByNamespaceAndFilePathAndChunkIndexIn(
            eq(NAMESPACE), anyString(), anyCollection()))
        .thenAnswer(
            inv ->
                filterByChunkIndexes(
                    storedEntities,
                    (String) inv.getArgument(1),
                    (Collection<Integer>) inv.getArgument(2)));
    when(documentRepository.findByNamespaceAndFilePath(eq(NAMESPACE), anyString()))
        .thenAnswer(
            inv -> filterByFile(storedEntities, (String) inv.getArgument(1)));
    when(documentRepository.findByNamespaceAndChunkHashIn(eq(NAMESPACE), anyCollection()))
        .thenAnswer(
            inv ->
                filterByChunkHashes(
                    storedEntities, (Collection<String>) inv.getArgument(1)));

    RepoRagSymbolGraphRepository searchGraphRepository = mock(RepoRagSymbolGraphRepository.class);
    when(searchGraphRepository.findByNamespaceAndSymbolFqn(eq(NAMESPACE), anyString()))
        .thenAnswer(
            inv ->
                filterBySymbol(
                    capturedEdges, edge -> edge.getSymbolFqn(), (String) inv.getArgument(1)));
    when(searchGraphRepository.findByNamespaceAndReferencedSymbolFqn(
            eq(NAMESPACE), anyString()))
        .thenAnswer(
            inv ->
                filterBySymbol(
                    capturedEdges,
                    RepoRagSymbolGraphEntity::getReferencedSymbolFqn,
                    (String) inv.getArgument(1)));

    GitHubRagProperties symbolProps = new GitHubRagProperties();
    symbolProps.getGraph().setEnabled(false);
    RepoRagSymbolService symbolService =
        new RepoRagSymbolService(searchGraphRepository, null, null, symbolProps);
    RepoRagDocumentMapper documentMapper = new RepoRagDocumentMapper(OBJECT_MAPPER);

    GitHubRagProperties searchProperties = new GitHubRagProperties();
    searchProperties.setParameterProfiles(
        List.of(profile("balanced"), profile("aggressive")));
    searchProperties.setDefaultProfile("balanced");
    searchProperties.afterPropertiesSet();
    searchProperties.getPostProcessing().getNeighbor().setAutoCallGraphEnabled(true);
    searchProperties.getPostProcessing().getNeighbor().setCallGraphLimit(4);
    searchProperties.getPostProcessing().setLlmCompressionEnabled(false);

    RepoRagSearchReranker reranker =
        new HeuristicRepoRagSearchReranker(
            searchProperties,
            new SingletonObjectProvider<>(ChatClient.builder(new StubChatModel())),
            documentRepository,
            documentMapper,
            symbolService);

    RepoRagRetrievalPipeline retrievalPipeline = mock(RepoRagRetrievalPipeline.class);
    Query finalQuery = Query.builder().text("DemoService helper").build();
    when(retrievalPipeline.buildQuery(any(), anyList(), any(), anyInt())).thenReturn(finalQuery);
    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        new RepoRagRetrievalPipeline.PipelineResult(
            finalQuery, indexedDocuments, List.of("retrieval.multi-query"), List.of(finalQuery));
    when(retrievalPipeline.execute(any())).thenReturn(pipelineResult);

    RepoRagNamespaceStateService namespaceStateService = mock(RepoRagNamespaceStateService.class);
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setNamespace(NAMESPACE);
    state.setRepoOwner(REPO_OWNER);
    state.setRepoName(REPO_NAME);
    state.setReady(true);
    state.setAstSchemaVersion(RepoRagIndexService.AST_VERSION);
    state.setAstReadyAt(Instant.now());
    when(namespaceStateService.findByRepoOwnerAndRepoName(REPO_OWNER, REPO_NAME))
        .thenReturn(Optional.of(state));

    RepoRagGenerationService generationService =
        new RepoRagGenerationService(searchProperties, new DefaultResourceLoader());
    RepoRagSearchService searchService =
        new RepoRagSearchService(
            searchProperties,
            retrievalPipeline,
            reranker,
            generationService,
            namespaceStateService,
            null);

    RagParameterGuard guard = new RagParameterGuard(searchProperties);
    RagParameterGuard.ResolvedSearchPlan plan =
        guard.apply(searchProperties.resolveProfile("balanced")).plan();

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            REPO_OWNER,
            REPO_NAME,
            "Найди helper метод DemoService",
            plan,
            List.of(),
            null,
            RepoRagResponseChannel.BOTH);

    RepoRagSearchService.SearchResponse response = searchService.search(command);
    assertThat(response.matches()).isNotEmpty();
    assertThat(response.appliedModules()).contains("profile:balanced");
    assertThat(response.warnings()).doesNotContain("neighbor.call-graph-disabled");
    boolean neighborPresent =
        response.matches().stream()
            .map(RepoRagSearchService.SearchMatch::metadata)
            .anyMatch(metadata -> metadata.containsKey("neighborOfSpanHash"));
    assertThat(neighborPresent).isTrue();
  }

  private List<RepoRagSymbolGraphEntity> filterBySymbol(
      List<RepoRagSymbolGraphEntity> edges,
      java.util.function.Function<RepoRagSymbolGraphEntity, String> extractor,
      String expected) {
    String normalized = normalize(expected);
    return edges.stream()
        .filter(edge -> normalized.equals(normalize(extractor.apply(edge))))
        .collect(Collectors.toList());
  }

  private List<RepoRagDocumentEntity> filterByFile(
      List<RepoRagDocumentEntity> entities, String filePath) {
    return entities.stream()
        .filter(entity -> entity.getFilePath().equals(filePath))
        .collect(Collectors.toList());
  }

  private List<RepoRagDocumentEntity> filterByChunkIndexes(
      List<RepoRagDocumentEntity> entities, String filePath, Collection<Integer> indexes) {
    return entities.stream()
        .filter(entity -> entity.getFilePath().equals(filePath))
        .filter(entity -> indexes.contains(entity.getChunkIndex()))
        .collect(Collectors.toList());
  }

  private List<RepoRagDocumentEntity> filterByChunkHashes(
      List<RepoRagDocumentEntity> entities, Collection<String> hashes) {
    return entities.stream()
        .filter(entity -> hashes.contains(entity.getChunkHash()))
        .collect(Collectors.toList());
  }

  private List<RepoRagDocumentEntity> convertDocuments(List<Document> docs) {
    List<RepoRagDocumentEntity> entities = new ArrayList<>();
    for (Document document : docs) {
      Map<String, Object> metadata = new HashMap<>(document.getMetadata());
      RepoRagDocumentEntity entity = new RepoRagDocumentEntity();
      try {
        java.lang.reflect.Field idField = RepoRagDocumentEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, UUID.randomUUID());
      } catch (ReflectiveOperationException ignored) {
        // reflectively setting id failure is not critical for assertions
      }
      entity.setNamespace((String) metadata.get("namespace"));
      entity.setFilePath((String) metadata.get("file_path"));
      entity.setChunkIndex(((Number) metadata.get("chunk_index")).intValue());
      entity.setChunkHash((String) metadata.get("chunk_hash"));
      entity.setLanguage((String) metadata.get("language"));
      entity.setSummary((String) metadata.get("summary"));
      entity.setContent(document.getText());
      entity.setMetadata(OBJECT_MAPPER.valueToTree(metadata));
      entities.add(entity);
    }
    return entities;
  }

  private GitHubRagProperties.RagParameterProfile profile(String name) {
    GitHubRagProperties.RagParameterProfile profile = new GitHubRagProperties.RagParameterProfile();
    profile.setName(name);
    profile.setTopK(24);
    profile.setTopKPerQuery(24);
    profile.setMinScore(0.55d);
    profile.setRerankTopN(8);
    profile.setCodeAwareEnabled(true);
    profile.getMultiQuery().setEnabled(true);
    profile.getMultiQuery().setQueries(3);
    profile.getMultiQuery().setMaxQueries(3);
    profile.getNeighbor().setStrategy("LINEAR");
    profile.getNeighbor().setRadius(1);
    profile.getNeighbor().setLimit(6);
    profile.setMinScoreFallback(0.45d);
    return profile;
  }

  private void copyDirectory(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path relative = source.relativize(dir);
            Files.createDirectories(target.resolve(relative));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path relative = source.relativize(file);
            Files.copy(file, target.resolve(relative));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {
    private final T instance;

    SingletonObjectProvider(T instance) {
      this.instance = instance;
    }

    @Override
    public T getObject(Object... args) {
      return instance;
    }

    @Override
    public T getIfAvailable() {
      return instance;
    }

    @Override
    public T getIfUnique() {
      return instance;
    }

    @Override
    public T getObject() {
      return instance;
    }
  }

  private static final class StubChatModel implements org.springframework.ai.chat.model.ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      Generation generation =
          new Generation(
              new AssistantMessage("stub"), ChatGenerationMetadata.builder().build());
      return new ChatResponse(List.of(generation));
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return ChatOptions.builder().build();
    }
  }
}
