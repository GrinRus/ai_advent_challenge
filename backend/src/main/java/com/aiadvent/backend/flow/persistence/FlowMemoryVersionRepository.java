package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowMemoryVersionRepository extends JpaRepository<FlowMemoryVersion, Long> {
  Optional<FlowMemoryVersion> findFirstByFlowSessionAndChannelOrderByVersionDesc(
      FlowSession flowSession, String channel);

  Optional<FlowMemoryVersion> findByFlowSessionAndChannelAndVersion(
      FlowSession flowSession, String channel, long version);

  List<FlowMemoryVersion> findByFlowSessionAndChannelOrderByVersionDesc(
      FlowSession flowSession, String channel);

  List<FlowMemoryVersion> findByFlowSessionAndChannelOrderByVersionAsc(
      FlowSession flowSession, String channel);

  List<FlowMemoryVersion> findByFlowSessionAndChannelAndVersionGreaterThanOrderByVersionAsc(
      FlowSession flowSession, String channel, long version);

  Page<FlowMemoryVersion> findByFlowSessionAndChannel(
      FlowSession flowSession, String channel, Pageable pageable);

  @Modifying
  @Query(
      "delete from FlowMemoryVersion v where v.flowSession = :session and v.channel = :channel and v.version < :minVersion")
  int deleteByFlowSessionAndChannelAndVersionLessThan(
      @Param("session") FlowSession session,
      @Param("channel") String channel,
      @Param("minVersion") long minVersion);

  @Modifying
  @Query(
      "delete from FlowMemoryVersion v where v.flowSession = :session and v.channel = :channel and v.createdAt < :cutoff")
  int deleteByFlowSessionAndChannelAndCreatedAtBefore(
      @Param("session") FlowSession session,
      @Param("channel") String channel,
      @Param("cutoff") Instant cutoff);
}
