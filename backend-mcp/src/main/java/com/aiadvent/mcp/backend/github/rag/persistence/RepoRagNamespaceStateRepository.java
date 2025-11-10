package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepoRagNamespaceStateRepository
    extends JpaRepository<RepoRagNamespaceStateEntity, UUID> {

  @Query(
      "select s from RepoRagNamespaceStateEntity s where s.repoOwner = :repoOwner and s.repoName = :repoName")
  Optional<RepoRagNamespaceStateEntity> findByRepoOwnerAndRepoName(
      @Param("repoOwner") String repoOwner, @Param("repoName") String repoName);

  Optional<RepoRagNamespaceStateEntity> findByNamespace(String namespace);
}
