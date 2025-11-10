package com.aiadvent.mcp.backend.github.rag.postprocessing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Compresses document snippets with an LLM to remove redundant lines.
 */
public class LlmSnippetCompressionPostProcessor implements DocumentPostProcessor {

  private static final Logger log =
      LoggerFactory.getLogger(LlmSnippetCompressionPostProcessor.class);

  private final ChatClient.Builder chatClientBuilder;
  private final int maxSnippetLines;
  private final String locale;
  private final boolean enabled;
  private final int maxCompressedDocuments;

  public LlmSnippetCompressionPostProcessor(
      ChatClient.Builder chatClientBuilder,
      int maxSnippetLines,
      String locale,
      boolean enabled,
      int maxCompressedDocuments) {
    this.chatClientBuilder = chatClientBuilder;
    this.maxSnippetLines = maxSnippetLines;
    this.locale = locale != null ? locale : "ru";
    this.enabled = enabled;
    this.maxCompressedDocuments = Math.max(1, maxCompressedDocuments);
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    if (!enabled || CollectionUtils.isEmpty(documents)) {
      return documents;
    }
    ChatClient chatClient = chatClientBuilder.clone().build();
    List<Document> result = new ArrayList<>(documents.size());
    for (int i = 0; i < documents.size(); i++) {
      Document document = documents.get(i);
      if (i >= maxCompressedDocuments || !needsCompression(document)) {
        result.add(document);
        continue;
      }
      try {
        String compressed = compress(chatClient, query, document);
        if (StringUtils.hasText(compressed)) {
          result.add(
              Document.builder()
                  .id(document.getId())
                  .text(compressed)
                  .metadata(document.getMetadata())
                  .score(document.getScore())
                  .build());
        } else {
          result.add(document);
        }
      } catch (RuntimeException ex) {
        log.debug("Snippet compression failed: {}", ex.getMessage());
        result.add(document);
      }
    }
    return List.copyOf(result);
  }

  private boolean needsCompression(Document document) {
    return document.getText() != null && document.getText().lines().count() > maxSnippetLines;
  }

  private String compress(ChatClient chatClient, Query query, Document document) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("Сожми следующий фрагмент кода до ")
        .append(maxSnippetLines)
        .append(" строк на языке ")
        .append(locale)
        .append(". Сохрани ключевые детали, достаточные для ответа на запрос: \"")
        .append(query.text())
        .append("\".\nФрагмент:\n```\n")
        .append(document.getText())
        .append("\n```\nОтвет:\n");
    var response = chatClient.prompt().user(builder.toString()).call();
    if (response == null
        || response.chatResponse() == null
        || response.chatResponse().getResult() == null
        || response.chatResponse().getResult().getOutput() == null) {
      return null;
    }
    return response.chatResponse().getResult().getOutput().getText();
  }
}
