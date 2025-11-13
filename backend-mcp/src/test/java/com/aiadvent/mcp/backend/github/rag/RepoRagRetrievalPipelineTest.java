package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

class RepoRagRetrievalPipelineTest {

  @Test
  void multiQueryDeduplicatesAndAnnotatesReferences() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getQueryTransformers().setEnabled(false);
    properties.getMultiQuery().setEnabled(true);

    StubVectorStore vectorStore =
        new StubVectorStore(
            Map.of(
                "original",
                    List.of(document("src/App.java", "hashA", 0.8)),
                "expanded-1",
                    List.of(
                        document("src/Util.java", "hashB", 0.7),
                        document("src/C.java", "hashC", 0.6)),
                "expanded-2",
                    List.of(document("src/C.java", "hashC", 0.95))));

    RepoRagRetrievalPipeline.MultiQueryExecutor executor =
        (baseQuery, count, builder) ->
            List.of(
                baseQuery,
                Query.builder().text("expanded-1").build(),
                Query.builder().text("expanded-2").build());

    RepoRagRetrievalPipeline pipeline =
        new RepoRagRetrievalPipeline(
            vectorStore, properties, new StaticObjectProvider<>(new NoopChatClientBuilder()), executor);

    RepoRagRetrievalPipeline.PipelineInput input =
        new RepoRagRetrievalPipeline.PipelineInput(
            Query.builder().text("original").build(),
            null,
            new RepoRagMultiQueryOptions(true, 3, 3),
            10,
            5,
            0.0,
            null,
            true);

    RepoRagRetrievalPipeline.PipelineResult result = pipeline.execute(input);

    assertThat(result.appliedModules()).contains("retrieval.multi-query");
    assertThat(result.documents()).hasSize(3);

    Document deduped =
        result.documents().stream()
            .filter(doc -> "hashC".equals(doc.getMetadata().get("chunk_hash")))
            .findFirst()
            .orElseThrow();

    Object generatedBy =
        deduped.getMetadata().get("generatedBySubQuery");
    assertThat(generatedBy)
        .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
        .hasSize(2)
        .anyMatch(entry -> "expanded-1".equals(entry.get("query")))
        .anyMatch(entry -> "expanded-2".equals(entry.get("query")));
    assertThat(deduped.getScore()).isEqualTo(0.95);
  }

  @Test
  void buildQueryClipsHistoryByTokenBudget() {
    GitHubRagProperties properties = new GitHubRagProperties();
    RepoRagRetrievalPipeline pipeline =
        new RepoRagRetrievalPipeline(
            new StubVectorStore(Map.of()),
            properties,
            new StaticObjectProvider<>(new NoopChatClientBuilder()),
            (query, count, builder) -> List.of(query));

    List<RepoRagSearchConversationTurn> history =
        List.of(
            new RepoRagSearchConversationTurn("user", "first message ".repeat(50)),
            new RepoRagSearchConversationTurn("assistant", "second message ".repeat(50)),
            new RepoRagSearchConversationTurn("user", "latest question"));

    Query result =
        pipeline.buildQuery(
            "raw-query", history, "previous assistant reply", 20);

    assertThat(result.history()).hasSize(2);
    assertThat(result.history().get(0).getText()).contains("latest question");
    assertThat(result.history().get(1).getText()).contains("previous assistant reply");
    assertThat(result.history())
        .noneMatch(message -> message.getText().contains("first message"));
  }

  @Test
  void skipsTransformersForCodeIdentifierQueries() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getQueryTransformers().setEnabled(true);
    properties.getQueryTransformers().setDefaultTargetLanguage("ru");

    StubVectorStore vectorStore =
        new StubVectorStore(Map.of("ChatProviderAdapter", List.of(document("src/App.java", "hashA", 0.9))));

    RepoRagRetrievalPipeline pipeline =
        new RepoRagRetrievalPipeline(
            vectorStore,
            properties,
            new StaticObjectProvider<>(new NoopChatClientBuilder()),
            (query, count, builder) -> List.of(query));

    RepoRagRetrievalPipeline.PipelineInput input =
        new RepoRagRetrievalPipeline.PipelineInput(
            Query.builder().text("ChatProviderAdapter").build(),
            null,
            new RepoRagMultiQueryOptions(false, null, null),
            5,
            5,
            0.0,
            null,
            true);

    RepoRagRetrievalPipeline.PipelineResult result = pipeline.execute(input);

    assertThat(result.appliedModules()).doesNotContain("query.rewrite", "query.translation");
    assertThat(result.documents()).hasSize(1);
  }

  private static Document document(String path, String chunkHash, double score) {
    return Document.builder()
        .id(path + ":" + chunkHash)
        .text("content for " + path)
        .metadata(
            Map.of(
                "file_path", path,
                "chunk_hash", chunkHash,
                "line_start", 1,
                "line_end", 10))
        .score(score)
        .build();
  }

  private static final class StubVectorStore implements VectorStore {
    private final Map<String, List<Document>> responses;

    private StubVectorStore(Map<String, List<Document>> responses) {
      this.responses = responses;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
      return responses.getOrDefault(request.getQuery(), List.of());
    }

    @Override
    public List<Document> similaritySearch(String query) {
      return responses.getOrDefault(query, List.of());
    }

    @Override
    public void add(List<Document> documents) {}

    @Override
    public void delete(List<String> idList) {}

    @Override
    public void delete(Filter.Expression expression) {}
  }

  private static final class StaticObjectProvider<T> implements ObjectProvider<T> {
    private final T instance;

    private StaticObjectProvider(T instance) {
      this.instance = instance;
    }

    @Override
    public T getObject() throws BeansException {
      return instance;
    }

    @Override
    public T getObject(Object... args) throws BeansException {
      return instance;
    }

    @Override
    public T getIfAvailable() throws BeansException {
      return instance;
    }

    @Override
    public T getIfUnique() throws BeansException {
      return instance;
    }

    @Override
    public void ifAvailable(Consumer<T> consumer) throws BeansException {
      consumer.accept(instance);
    }

    @Override
    public void ifUnique(Consumer<T> consumer) throws BeansException {
      consumer.accept(instance);
    }

    @Override
    public Iterator<T> iterator() {
      return List.of(instance).iterator();
    }

    @Override
    public Stream<T> stream() {
      return Stream.of(instance);
    }

    @Override
    public Stream<T> orderedStream() {
      return Stream.of(instance);
    }

    @Override
    public Stream<T> stream(Predicate<Class<?>> predicate) {
      return Stream.of(instance);
    }

    @Override
    public Stream<T> orderedStream(Predicate<Class<?>> predicate) {
      return Stream.of(instance);
    }

    @Override
    public Stream<T> stream(Predicate<Class<?>> predicate, boolean includeNonSingletons) {
      return Stream.of(instance);
    }

    @Override
    public Stream<T> orderedStream(Predicate<Class<?>> predicate, boolean includeNonSingletons) {
      return Stream.of(instance);
    }
  }

  private static final class NoopChatClientBuilder implements ChatClient.Builder {

    @Override
    public ChatClient.Builder defaultAdvisors(
        org.springframework.ai.chat.client.advisor.api.Advisor... advisors) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultAdvisors(
        Consumer<org.springframework.ai.chat.client.ChatClient.AdvisorSpec> advisors) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultAdvisors(
        List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultOptions(
        org.springframework.ai.chat.prompt.ChatOptions chatOptions) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(String user) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(
        org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(org.springframework.core.io.Resource resource) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultUser(
        Consumer<org.springframework.ai.chat.client.ChatClient.PromptUserSpec> userSpec) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(String system) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(
        org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(org.springframework.core.io.Resource resource) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultSystem(
        Consumer<org.springframework.ai.chat.client.ChatClient.PromptSystemSpec> systemSpec) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultTemplateRenderer(
        org.springframework.ai.template.TemplateRenderer templateRenderer) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolNames(String... toolNames) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultTools(Object... toolObjects) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolCallbacks(
        org.springframework.ai.tool.ToolCallback... toolCallbacks) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolCallbacks(
        List<org.springframework.ai.tool.ToolCallback> toolCallbacks) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolCallbacks(
        org.springframework.ai.tool.ToolCallbackProvider... toolCallbackProviders) {
      return this;
    }

    @Override
    public ChatClient.Builder defaultToolContext(Map<String, Object> context) {
      return this;
    }

    @Override
    public ChatClient.Builder clone() {
      return this;
    }

    @Override
    public ChatClient build() {
      throw new UnsupportedOperationException("Not used in tests");
    }
  }
}
