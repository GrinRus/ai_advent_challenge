package com.aiadvent.mcp.backend.rbac.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link AuditLogEntity}.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    /**
     * Find audit log entries by actor namespace and reference.
     *
     * @param actorNamespace the actor's namespace
     * @param actorReference the actor's reference
     * @param pageable       pagination information
     * @return a page of audit log entries
     */
    Page<AuditLogEntity> findByActorNamespaceAndActorReference(
            String actorNamespace, String actorReference, Pageable pageable);

    /**
     * Find audit log entries within a timestamp range.
     *
     * @param start    the start timestamp (inclusive)
     * @param end      the end timestamp (inclusive)
     * @param pageable pagination information
     * @return a page of audit log entries
     */
    Page<AuditLogEntity> findByTimestampBetween(
            Instant start, Instant end, Pageable pageable);
}
