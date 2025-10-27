package com.aiadvent.backend.flow.tool.persistence;

import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ToolSchemaVersionRepository extends JpaRepository<ToolSchemaVersion, UUID> {

  Optional<ToolSchemaVersion> findTopByToolCodeOrderByVersionDesc(String toolCode);
}

