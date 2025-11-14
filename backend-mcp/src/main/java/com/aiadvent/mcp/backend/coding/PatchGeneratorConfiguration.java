package com.aiadvent.mcp.backend.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class PatchGeneratorConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PatchGeneratorConfiguration.class);

  @Bean
  @Primary
  PatchGenerator patchGenerator(
      CodingAssistantProperties properties,
      ClaudeCliPatchGenerator claudeCliPatchGenerator,
      PatchPlanGenerator patchPlanGenerator) {
    boolean cliEnabled = properties.getClaude() != null && properties.getClaude().isEnabled();
    if (cliEnabled) {
      return claudeCliPatchGenerator;
    }
    log.warn("Claude CLI generator disabled via configuration — using patch plan fallback.");
    return patchPlanGenerator;
  }
}
