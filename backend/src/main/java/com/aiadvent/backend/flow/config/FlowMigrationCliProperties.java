package com.aiadvent.backend.flow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.flow.migration.cli")
public class FlowMigrationCliProperties {

  private boolean enabled = false;
  private boolean dryRun = true;
  private boolean includeHistory = true;
  private boolean failOnError = false;
  private List<UUID> definitionIds = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public boolean isIncludeHistory() {
    return includeHistory;
  }

  public void setIncludeHistory(boolean includeHistory) {
    this.includeHistory = includeHistory;
  }

  public boolean isFailOnError() {
    return failOnError;
  }

  public void setFailOnError(boolean failOnError) {
    this.failOnError = failOnError;
  }

  public List<UUID> getDefinitionIds() {
    return definitionIds;
  }

  public void setDefinitionIds(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      this.definitionIds = new ArrayList<>();
      return;
    }
    List<UUID> parsed = new ArrayList<>(ids.size());
    for (String id : ids) {
      if (id == null || id.isBlank()) {
        continue;
      }
      parsed.add(UUID.fromString(id.trim()));
    }
    this.definitionIds = parsed;
  }
}
