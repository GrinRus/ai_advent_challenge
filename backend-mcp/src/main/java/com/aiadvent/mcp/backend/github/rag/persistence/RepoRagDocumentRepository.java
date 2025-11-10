package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepoRagDocumentRepository
    extends JpaRepository<RepoRagDocumentEntity, UUID> {

  long deleteByNamespace(String namespace);

  Optional<RepoRagDocumentEntity> findByNamespaceAndChunkHash(String namespace, String chunkHash);

  @Query("select d.id from RepoRagDocumentEntity d where d.namespace = :namespace")
  List<UUID> findIdsByNamespace(@Param("namespace") String namespace);
}
