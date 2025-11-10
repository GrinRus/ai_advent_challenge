package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RepoRagFileStateRepository
    extends JpaRepository<RepoRagFileStateEntity, UUID> {

  @Query("select f from RepoRagFileStateEntity f where f.namespace = :namespace")
  List<RepoRagFileStateEntity> findByNamespace(@Param("namespace") String namespace);

  @Query(
      "select f from RepoRagFileStateEntity f where f.namespace = :namespace and f.filePath = :filePath")
  Optional<RepoRagFileStateEntity> findByNamespaceAndFilePath(
      @Param("namespace") String namespace, @Param("filePath") String filePath);

  @Modifying
  @Transactional
  @Query(
      "delete from RepoRagFileStateEntity f where f.namespace = :namespace and f.filePath = :filePath")
  void deleteByNamespaceAndFilePath(
      @Param("namespace") String namespace, @Param("filePath") String filePath);
}
