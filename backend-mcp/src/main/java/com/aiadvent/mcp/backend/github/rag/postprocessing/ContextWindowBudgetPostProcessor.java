package com.aiadvent.mcp.backend.github.rag.postprocessing;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.CollectionUtils;

/**
 * Truncates the document list to keep the cumulative token budget under control.
 */
public class ContextWindowBudgetPostProcessor implements DocumentPostProcessor {

  private final int maxContextTokens;

  public ContextWindowBudgetPostProcessor(int maxContextTokens) {
    this.maxContextTokens = maxContextTokens;
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    if (maxContextTokens <= 0 || CollectionUtils.isEmpty(documents)) {
      return documents;
    }
    List<Document> result = new ArrayList<>();
    int remaining = maxContextTokens;
    for (Document document : documents) {
      int tokens = estimateTokens(document.getText());
      if (!result.isEmpty() && tokens > remaining) {
        break;
      }
      result.add(document);
      remaining -= tokens;
      if (remaining <= 0) {
        break;
      }
    }
    return List.copyOf(result);
  }

  private int estimateTokens(String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    return Math.max(1, text.length() / 4);
  }
}

