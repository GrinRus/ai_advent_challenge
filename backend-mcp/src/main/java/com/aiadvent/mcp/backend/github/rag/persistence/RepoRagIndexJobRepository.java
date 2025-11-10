package com.aiadvent.mcp.backend.github.rag.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoRagIndexJobRepository
    extends JpaRepository<RepoRagIndexJobEntity, UUID> {

  Optional<RepoRagIndexJobEntity> findFirstByRepoOwnerIgnoreCaseAndRepoNameIgnoreCaseOrderByQueuedAtDesc(
      String repoOwner, String repoName);

  List<RepoRagIndexJobEntity> findByStatusIn(List<RepoRagJobStatus> statuses);
}
