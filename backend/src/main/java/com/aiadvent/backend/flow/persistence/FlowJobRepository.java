package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowJobRepository extends JpaRepository<FlowJob, Long> {
  List<FlowJob> findByStatusOrderByScheduledAtAsc(FlowJobStatus status);

  List<FlowJob> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
      FlowJobStatus status, Instant scheduledBefore);

  @Query(
      value =
          """
          SELECT *
          FROM flow_job
          WHERE status = :status
            AND (scheduled_at IS NULL OR scheduled_at <= :now)
          ORDER BY scheduled_at NULLS FIRST, id
          FOR UPDATE SKIP LOCKED
          LIMIT 1
          """,
      nativeQuery = true)
  Optional<FlowJob> lockNextJob(
      @Param("status") String statusValue, @Param("now") Instant now);

  default Optional<FlowJob> lockNextJob(FlowJobStatus status, Instant now) {
    return lockNextJob(status.name(), now);
  }
}
