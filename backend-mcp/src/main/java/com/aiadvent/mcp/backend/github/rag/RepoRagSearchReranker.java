package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.postprocessing.RepoRagPostProcessingRequest;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

public interface RepoRagSearchReranker {

  PostProcessingResult process(
      Query query, List<Document> documents, RepoRagPostProcessingRequest request);

  record PostProcessingResult(
      List<Document> documents, boolean changed, List<String> appliedModules) {}
}
