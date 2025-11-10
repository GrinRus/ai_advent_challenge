package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class RepoRagVectorStoreAdapter {

  private final VectorStore vectorStore;
  private final RepoRagDocumentRepository documentRepository;

  public RepoRagVectorStoreAdapter(
      @Qualifier("repoRagVectorStore") VectorStore vectorStore,
      RepoRagDocumentRepository documentRepository) {
    this.vectorStore = vectorStore;
    this.documentRepository = documentRepository;
  }

  public void replaceNamespace(String namespace, List<Document> documents) {
    deleteNamespace(namespace);
    addDocuments(documents);
  }

  public void addDocuments(List<Document> documents) {
    if (CollectionUtils.isEmpty(documents)) {
      return;
    }
    vectorStore.add(documents);
  }

  public void deleteNamespace(String namespace) {
    List<UUID> ids = documentRepository.findIdsByNamespace(namespace);
    if (ids.isEmpty()) {
      return;
    }
    vectorStore.delete(ids.stream().map(UUID::toString).collect(Collectors.toList()));
  }
}
