package com.aiadvent.backend.flow.tool.persistence;

import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ToolDefinitionRepository extends JpaRepository<ToolDefinition, UUID> {

  @EntityGraph(attributePaths = "schemaVersion")
  Optional<ToolDefinition> findByCodeIgnoreCase(String code);

  List<ToolDefinition> findAllByOrderByDisplayNameAsc();

  @EntityGraph(attributePaths = "schemaVersion")
  List<ToolDefinition> findAllBySchemaVersionIsNotNull();
}
