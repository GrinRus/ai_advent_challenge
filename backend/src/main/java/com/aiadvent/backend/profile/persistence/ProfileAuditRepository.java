package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.ProfileAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileAuditRepository extends JpaRepository<ProfileAuditEvent, UUID> {

  List<ProfileAuditEvent> findByProfileIdOrderByCreatedAtDesc(UUID profileId, Pageable pageable);
}
