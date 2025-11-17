package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoRagSymbolGraphRepository
    extends JpaRepository<RepoRagSymbolGraphEntity, UUID> {

  List<RepoRagSymbolGraphEntity> findByNamespaceAndSymbolFqn(String namespace, String symbolFqn);

  List<RepoRagSymbolGraphEntity> findByNamespaceAndReferencedSymbolFqn(
      String namespace, String referencedSymbolFqn);

  void deleteByNamespaceAndFilePath(String namespace, String filePath);
}
