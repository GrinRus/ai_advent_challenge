package com.aiadvent.mcp.backend.notes.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NoteRepository extends JpaRepository<NoteEntity, UUID> {

  Optional<NoteEntity> findByUserNamespaceAndUserReferenceAndContentHash(
      String userNamespace, String userReference, String contentHash);

  List<NoteEntity> findByIdIn(Collection<UUID> ids);
}
