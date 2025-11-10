package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  public Set<String> listFilePaths(String namespace) {
    return new HashSet<>(documentRepository.findDistinctFilePathsByNamespace(namespace));
  }

  public void replaceFile(String namespace, String filePath, List<Document> documents) {
    deleteFile(namespace, filePath);
    addDocuments(documents);
  }

  public void deleteFile(String namespace, String filePath) {
    List<UUID> ids = documentRepository.findIdsByNamespaceAndFilePath(namespace, filePath);
    if (ids.isEmpty()) {
      return;
    }
    vectorStore.delete(ids.stream().map(UUID::toString).collect(Collectors.toList()));
  }
}
