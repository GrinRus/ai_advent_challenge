package com.aiadvent.backend.flow.cli;

import com.aiadvent.backend.flow.config.FlowMigrationCliProperties;
import com.aiadvent.backend.flow.service.FlowBlueprintMigrationService;
import com.aiadvent.backend.flow.service.FlowBlueprintMigrationService.FlowBlueprintMigrationRequest;
import com.aiadvent.backend.flow.service.FlowBlueprintMigrationService.FlowBlueprintMigrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.flow.migration.cli", name = "enabled", havingValue = "true")
public class FlowBlueprintMigrationCliRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(FlowBlueprintMigrationCliRunner.class);

  private final FlowBlueprintMigrationService migrationService;
  private final FlowMigrationCliProperties properties;

  public FlowBlueprintMigrationCliRunner(
      FlowBlueprintMigrationService migrationService, FlowMigrationCliProperties properties) {
    this.migrationService = migrationService;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    FlowBlueprintMigrationRequest request =
        new FlowBlueprintMigrationRequest(
            properties.getDefinitionIds(),
            properties.isIncludeHistory(),
            properties.isDryRun(),
            properties.isFailOnError());

    log.info(
        "Starting flow blueprint migration (definitions={}, dryRun={}, includeHistory={}, failOnError={})",
        request.definitionIds().isEmpty() ? "ALL" : request.definitionIds(),
        request.dryRun(),
        request.includeHistory(),
        request.failOnError());

    FlowBlueprintMigrationResult result = migrationService.migrate(request);

    log.info(
        "Flow blueprint migration completed: processedDefinitions={}, needingUpdate={}, updated={}, processedHistoryEntries={}, historyNeedingUpdate={}, historyUpdated={}, validationFailures={}, failures={}",
        result.processedDefinitions(),
        result.definitionsNeedingUpdate(),
        result.definitionsUpdated(),
        result.processedHistoryEntries(),
        result.historyEntriesNeedingUpdate(),
        result.historyEntriesUpdated(),
        result.validationFailures(),
        result.failures());
  }
}
