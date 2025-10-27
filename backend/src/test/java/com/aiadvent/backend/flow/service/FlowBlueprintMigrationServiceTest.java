package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMetadata;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionHistoryRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FlowBlueprintMigrationServiceTest extends PostgresTestContainer {

  @Autowired private FlowBlueprintMigrationService migrationService;
  @Autowired private FlowDefinitionRepository flowDefinitionRepository;
  @Autowired private FlowDefinitionHistoryRepository flowDefinitionHistoryRepository;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  @BeforeEach
  void clean() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE flow_definition_history, flow_definition, agent_capability, agent_version, agent_definition RESTART IDENTITY CASCADE");
  }

  @Test
  void migratesBlueprintSchemaVersionAndSupportsDryRun() {
    AgentDefinition agentDefinition =
        agentDefinitionRepository.save(
            new AgentDefinition("test-agent", "Test Agent", "", true));
    AgentVersion agentVersion =
        new AgentVersion(
            agentDefinition,
            1,
            AgentVersionStatus.PUBLISHED,
            ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    agentVersion.setInvocationOptions(AgentInvocationOptions.empty());
    agentVersionRepository.save(agentVersion);

    FlowBlueprint blueprint =
        new FlowBlueprint(
            2,
            FlowBlueprintMetadata.empty(),
            "Test Flow",
            "Test Flow Description",
            "step-1",
            true,
            List.of(),
            FlowBlueprintMemory.empty(),
            List.of(
                new FlowBlueprintStep(
                    "step-1",
                    "Gather",
                    agentVersion.getId().toString(),
                    "Gather context",
                    null,
                    null,
                    null,
                    null,
                    null,
                    1)));

    FlowDefinition definition =
        new FlowDefinition("test-flow", 1, FlowDefinitionStatus.DRAFT, true, blueprint);
    definition.setUpdatedBy("tester");
    FlowDefinition savedDefinition = flowDefinitionRepository.save(definition);

    FlowDefinitionHistory history =
        new FlowDefinitionHistory(
            savedDefinition,
            savedDefinition.getVersion(),
            savedDefinition.getStatus(),
            blueprint,
            2,
            "Initial",
            "tester");
    FlowDefinitionHistory savedHistory = flowDefinitionHistoryRepository.save(history);

    jdbcTemplate.update(
        "UPDATE flow_definition SET blueprint_schema_version = ? WHERE id = ?", 0, savedDefinition.getId());
    jdbcTemplate.update(
        "UPDATE flow_definition_history SET blueprint_schema_version = ? WHERE id = ?",
        0,
        savedHistory.getId());

    entityManager.clear();

    FlowBlueprintMigrationService.FlowBlueprintMigrationRequest dryRunRequest =
        new FlowBlueprintMigrationService.FlowBlueprintMigrationRequest(
            List.of(savedDefinition.getId()), true, true, true);
    FlowBlueprintMigrationService.FlowBlueprintMigrationResult dryRunResult =
        migrationService.migrate(dryRunRequest);

    assertThat(dryRunResult.definitionsNeedingUpdate()).isEqualTo(1);
    assertThat(dryRunResult.definitionsUpdated()).isZero();
    assertThat(dryRunResult.historyEntriesNeedingUpdate()).isEqualTo(1);
    assertThat(dryRunResult.historyEntriesUpdated()).isZero();

    entityManager.clear();

    FlowBlueprintMigrationService.FlowBlueprintMigrationRequest applyRequest =
        new FlowBlueprintMigrationService.FlowBlueprintMigrationRequest(
            List.of(savedDefinition.getId()), true, false, true);
    FlowBlueprintMigrationService.FlowBlueprintMigrationResult applyResult =
        migrationService.migrate(applyRequest);

    assertThat(applyResult.definitionsUpdated()).isEqualTo(1);
    assertThat(applyResult.historyEntriesUpdated()).isEqualTo(1);

    FlowDefinition migratedDefinition =
        flowDefinitionRepository.findById(savedDefinition.getId()).orElseThrow();
    FlowDefinitionHistory migratedHistory =
        flowDefinitionHistoryRepository.findById(savedHistory.getId()).orElseThrow();

    assertThat(migratedDefinition.getBlueprintSchemaVersion()).isEqualTo(2);
    assertThat(migratedHistory.getBlueprintSchemaVersion()).isEqualTo(2);
  }
}
