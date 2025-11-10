package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.config.GitHubRagProperties.MultiQuery;
import com.aiadvent.mcp.backend.config.GitHubRagProperties.QueryTransformers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class RepoRagRetrievalPipeline {

  private static final Logger log = LoggerFactory.getLogger(RepoRagRetrievalPipeline.class);
  private static final String SUB_QUERY_METADATA_KEY = "generatedBySubQuery";
  private static final Set<String> SUPPORTED_HISTORY_ROLES =
      Set.of("system", "user", "assistant");

  private final VectorStore vectorStore;
  private final GitHubRagProperties properties;
  private final ObjectProvider<ChatClient.Builder> queryTransformerChatClientBuilder;

  public RepoRagRetrievalPipeline(
      VectorStore vectorStore,
      GitHubRagProperties properties,
      @Qualifier("repoRagQueryTransformerChatClientBuilder")
          ObjectProvider<ChatClient.Builder> repoRagQueryTransformerChatClientBuilder) {
    this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.queryTransformerChatClientBuilder =
        Objects.requireNonNull(
            repoRagQueryTransformerChatClientBuilder, "repoRagQueryTransformerChatClientBuilder");
  }

  public PipelineResult execute(PipelineInput input) {
    Objects.requireNonNull(input, "input");
    Query baseQuery = input.query();
    List<String> appliedModules = new ArrayList<>();

    Query transformedQuery = applyQueryTransformers(baseQuery, input, appliedModules);

    List<Query> queries = expandQueries(transformedQuery, input, appliedModules);
    Map<String, AggregatedDocument> dedup = new LinkedHashMap<>();
    for (int i = 0; i < queries.size(); i++) {
      Query query = queries.get(i);
      List<Document> retrieved = retrieveDocuments(query, input);
      for (Document document : retrieved) {
        accumulateDocument(dedup, document, query, i);
      }
    }

    List<Document> merged =
        dedup.values().stream().map(AggregatedDocument::toDocument).toList();

    return new PipelineResult(transformedQuery, merged, appliedModules, queries);
  }

  private Query applyQueryTransformers(
      Query baseQuery, PipelineInput input, List<String> appliedModules) {
    Query current = baseQuery;
    QueryTransformers transformers = properties.getQueryTransformers();
    if (!transformers.isEnabled()) {
      return current;
    }
    ChatClient.Builder builder = queryTransformerChatClientBuilder.getObject();
    if (!CollectionUtils.isEmpty(current.history())) {
      QueryTransformer compression =
          CompressionQueryTransformer.builder()
              .chatClientBuilder(builder.clone())
              .build();
      Query compressed = compression.transform(current);
      if (!Objects.equals(compressed.text(), current.text())) {
        appliedModules.add("query.compression");
      }
      current = compressed;
    }

    QueryTransformer rewrite =
        RewriteQueryTransformer.builder().chatClientBuilder(builder.clone()).build();
    Query rewritten = rewrite.transform(current);
    if (!Objects.equals(rewritten.text(), current.text())) {
      appliedModules.add("query.rewrite");
    }
    current = rewritten;

    String targetLanguage =
        StringUtils.hasText(input.translateTo())
            ? input.translateTo()
            : transformers.getDefaultTargetLanguage();
    if (StringUtils.hasText(targetLanguage)) {
      TranslationQueryTransformer translation =
          TranslationQueryTransformer.builder()
              .chatClientBuilder(builder.clone())
              .targetLanguage(targetLanguage)
              .build();
      Query translated = translation.transform(current);
      if (!Objects.equals(translated.text(), current.text())) {
        appliedModules.add("query.translation");
      }
      current = translated;
    }
    return current;
  }

  private List<Query> expandQueries(
      Query query, PipelineInput input, List<String> appliedModules) {
    MultiQuery multiQueryProperties = properties.getMultiQuery();
    boolean enabled = multiQueryProperties.isEnabled();
    boolean requestEnabled =
        input.multiQueryOptions() != null && Boolean.TRUE.equals(input.multiQueryOptions().enabled());
    boolean requestDisabled =
        input.multiQueryOptions() != null && Boolean.FALSE.equals(input.multiQueryOptions().enabled());

    if ((!enabled && !requestEnabled) || requestDisabled) {
      return List.of(query);
    }

    int requestedCount =
        input.multiQueryOptions() != null && input.multiQueryOptions().queries() != null
            ? input.multiQueryOptions().queries()
            : multiQueryProperties.getDefaultQueries();
    int count =
        Math.max(
            1, Math.min(requestedCount, Math.max(1, multiQueryProperties.getMaxQueries())));
    if (count <= 1) {
      return List.of(query);
    }
    MultiQueryExpander expander =
        MultiQueryExpander.builder()
            .chatClientBuilder(queryTransformerChatClientBuilder.getObject().clone())
            .numberOfQueries(count)
            .includeOriginal(true)
            .build();
    List<Query> expanded = expander.expand(query);
    appliedModules.add("retrieval.multi-query");
    return expanded;
  }

  private List<Document> retrieveDocuments(Query query, PipelineInput input) {
    VectorStoreDocumentRetriever.Builder builder =
        VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .topK(input.topK())
            .similarityThreshold(input.minScore());
    if (input.filterExpression() != null) {
      builder.filterExpression(input.filterExpression());
    }
    VectorStoreDocumentRetriever retriever = builder.build();
    return retriever.retrieve(query);
  }

  private void accumulateDocument(
      Map<String, AggregatedDocument> dedup,
      Document document,
      Query query,
      int queryIndex) {
    String chunkHash = extractChunkHash(document);
    AggregatedDocument aggregated =
        dedup.computeIfAbsent(chunkHash, key -> AggregatedDocument.from(document));
    aggregated.addReference(Map.of("index", queryIndex, "query", query.text()));
    aggregated.maybePromote(document);
  }

  private String extractChunkHash(Document document) {
    Object value = document.getMetadata().get("chunk_hash");
    if (value instanceof String str && StringUtils.hasText(str)) {
      return str;
    }
    return document.getId() != null ? document.getId() : Integer.toHexString(document.hashCode());
  }

  private static final class AggregatedDocument {
    private Document current;
    private Double leadingScore;
    private final List<Map<String, Object>> references = new ArrayList<>();

    private AggregatedDocument(Document current, Double leadingScore) {
      this.current = current;
      this.leadingScore = leadingScore;
    }

    static AggregatedDocument from(Document document) {
      return new AggregatedDocument(document, document.getScore());
    }

    void addReference(Map<String, Object> reference) {
      references.add(reference);
    }

    void maybePromote(Document candidate) {
      if (candidate.getScore() == null) {
        return;
      }
      if (leadingScore == null || candidate.getScore() > leadingScore) {
        current = candidate;
        leadingScore = candidate.getScore();
      }
    }

    Document toDocument() {
      Map<String, Object> metadata = new LinkedHashMap<>();
      if (current.getMetadata() != null) {
        metadata.putAll(current.getMetadata());
      }
      metadata.put(SUB_QUERY_METADATA_KEY, List.copyOf(references));
      return Document.builder()
          .id(current.getId())
          .text(current.getText())
          .metadata(metadata)
          .score(current.getScore())
          .build();
    }
  }

  public record PipelineInput(
      Query query,
      Filter.Expression filterExpression,
      RepoRagMultiQueryOptions multiQueryOptions,
      int topK,
      double minScore,
      String translateTo) {}

  public record PipelineResult(
      Query finalQuery,
      List<Document> documents,
      List<String> appliedModules,
      List<Query> executedQueries) {}

  public Query buildQuery(
      String rawQuery,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      int maxHistoryTokens) {
    List<Message> normalizedHistory =
        normalizeHistory(history, previousAssistantReply, maxHistoryTokens);
    return Query.builder().text(rawQuery).history(normalizedHistory).build();
  }

  private List<Message> normalizeHistory(
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      int maxHistoryTokens) {
    if (CollectionUtils.isEmpty(history) && !StringUtils.hasText(previousAssistantReply)) {
      return List.of();
    }
    List<Message> messages = new ArrayList<>();
    if (!CollectionUtils.isEmpty(history)) {
      for (RepoRagSearchConversationTurn turn : history) {
        String role = turn.normalizedRole();
        if (!SUPPORTED_HISTORY_ROLES.contains(role)) {
          continue;
        }
        messages.add(switch (role) {
          case "system" -> new SystemMessage(turn.content());
          case "assistant" -> new AssistantMessage(turn.content());
          default -> new UserMessage(turn.content());
        });
      }
    }
    if (StringUtils.hasText(previousAssistantReply)) {
      messages.add(new AssistantMessage(previousAssistantReply));
    }
    if (maxHistoryTokens <= 0 || messages.isEmpty()) {
      return List.copyOf(messages);
    }
    List<Message> reversed = new ArrayList<>(messages);
    Collections.reverse(reversed);
    List<Message> clipped = new ArrayList<>();
    int remaining = maxHistoryTokens;
    for (Message message : reversed) {
      int tokenEstimate = estimateTokens(message.getText());
      remaining -= tokenEstimate;
      if (remaining < 0) {
        break;
      }
      clipped.add(message);
    }
    Collections.reverse(clipped);
    return List.copyOf(clipped);
  }

  private int estimateTokens(String content) {
    if (!StringUtils.hasText(content)) {
      return 0;
    }
    return Math.max(1, content.length() / 4);
  }
}
