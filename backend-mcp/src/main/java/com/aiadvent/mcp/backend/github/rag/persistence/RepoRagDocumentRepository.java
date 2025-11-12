package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.Collection;
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

  @Query(
      "select d.id from RepoRagDocumentEntity d where d.namespace = :namespace and d.filePath = :filePath")
  List<UUID> findIdsByNamespaceAndFilePath(
      @Param("namespace") String namespace, @Param("filePath") String filePath);

  @Query("select distinct d.filePath from RepoRagDocumentEntity d where d.namespace = :namespace")
  List<String> findDistinctFilePathsByNamespace(@Param("namespace") String namespace);

  @Query(
      "select d from RepoRagDocumentEntity d where d.namespace = :namespace and d.filePath = :filePath and d.chunkIndex in :chunkIndexes")
  List<RepoRagDocumentEntity> findByNamespaceAndFilePathAndChunkIndexIn(
      @Param("namespace") String namespace,
      @Param("filePath") String filePath,
      @Param("chunkIndexes") Collection<Integer> chunkIndexes);

  List<RepoRagDocumentEntity> findByNamespaceAndFilePath(String namespace, String filePath);
}
