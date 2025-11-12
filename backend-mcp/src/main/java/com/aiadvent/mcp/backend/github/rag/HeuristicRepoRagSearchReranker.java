package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.postprocessing.CodeAwareDocumentPostProcessor;
import com.aiadvent.mcp.backend.github.rag.postprocessing.ContextWindowBudgetPostProcessor;
import com.aiadvent.mcp.backend.github.rag.postprocessing.HeuristicDocumentPostProcessor;
import com.aiadvent.mcp.backend.github.rag.postprocessing.LlmSnippetCompressionPostProcessor;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentMapper;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentRepository;
import com.aiadvent.mcp.backend.github.rag.postprocessing.NeighborChunkDocumentPostProcessor;
import com.aiadvent.mcp.backend.github.rag.postprocessing.RepoRagPostProcessingRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.CollectionUtils;

public class HeuristicRepoRagSearchReranker implements RepoRagSearchReranker {

  private final GitHubRagProperties properties;
  private final ObjectProvider<ChatClient.Builder> snippetCompressorBuilderProvider;
  private final RepoRagDocumentRepository documentRepository;
  private final RepoRagDocumentMapper documentMapper;
  private final RepoRagSymbolService symbolService;

  public HeuristicRepoRagSearchReranker(
      GitHubRagProperties properties,
      ObjectProvider<ChatClient.Builder> snippetCompressorBuilderProvider,
      RepoRagDocumentRepository documentRepository,
      RepoRagDocumentMapper documentMapper,
      RepoRagSymbolService symbolService) {
    this.properties = properties;
    this.snippetCompressorBuilderProvider = snippetCompressorBuilderProvider;
    this.documentRepository = documentRepository;
    this.documentMapper = documentMapper;
    this.symbolService = symbolService;
  }

  @Override
  public PostProcessingResult process(
      Query query, List<Document> documents, RepoRagPostProcessingRequest request) {
    if (CollectionUtils.isEmpty(documents)) {
      return new PostProcessingResult(documents, false, List.of());
    }
    List<Document> current = documents;
    boolean changed = false;
    List<String> modules = new ArrayList<>();
    for (NamedProcessor processor : buildProcessors(request)) {
      List<Document> updated = processor.delegate().process(query, current);
      if (hasChanged(current, updated)) {
        changed = true;
        modules.add(processor.name());
      }
      current = updated;
    }
    return new PostProcessingResult(current, changed, modules);
  }

  private List<NamedProcessor> buildProcessors(RepoRagPostProcessingRequest request) {
    List<NamedProcessor> processors = new ArrayList<>();
    if (request.codeAwareEnabled()) {
      processors.add(
          new NamedProcessor(
              "post.code-aware",
              new CodeAwareDocumentPostProcessor(
                  properties.getRerank().getCodeAware(),
                  request.rerankTopN(),
                  request.codeAwareHeadMultiplier(),
                  request.requestedLanguage())));
    }
    processors.add(
        new NamedProcessor(
            "post.heuristic-rerank",
            new HeuristicDocumentPostProcessor(properties.getRerank(), request.rerankTopN())));
    if (request.neighborEnabled()) {
      processors.add(
          new NamedProcessor(
              "post.neighbor-expand",
              new NeighborChunkDocumentPostProcessor(
                  documentRepository,
                  documentMapper,
                  symbolService,
                  request.neighborStrategy(),
                  request.neighborRadius(),
                  request.neighborLimit())));
    }
    if (request.maxContextTokens() > 0) {
      processors.add(
          new NamedProcessor(
              "post.context-budget",
              new ContextWindowBudgetPostProcessor(request.maxContextTokens())));
    }
    if (request.compressionEnabled()) {
      processors.add(
          new NamedProcessor(
              "post.llm-compression",
              new LlmSnippetCompressionPostProcessor(
                  snippetCompressorBuilderProvider.getObject(),
                  request.maxSnippetLines(),
                  request.locale(),
                  true,
                  6)));
    }
    return processors;
  }

  private boolean hasChanged(List<Document> before, List<Document> after) {
    if (before == after) {
      return false;
    }
    if (before.size() != after.size()) {
      return true;
    }
    return IntStream.range(0, before.size())
        .anyMatch(
            index ->
                !Objects.equals(fingerprint(before.get(index)), fingerprint(after.get(index))));
  }

  private String fingerprint(Document document) {
    String path = document.getMetadata() != null ? (String) document.getMetadata().get("file_path") : "";
    return path + "::" + document.getText();
  }

  private record NamedProcessor(String name, DocumentPostProcessor delegate) {}
}
