package com.aiadvent.backend.flow.cli;

import com.aiadvent.backend.flow.config.FlowSummaryCliProperties;
import com.aiadvent.backend.flow.memory.FlowMemorySummarizerService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "app.flow.summary.cli", name = "enabled", havingValue = "true")
public class FlowSummaryCliRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(FlowSummaryCliRunner.class);

  private final FlowMemorySummarizerService summarizerService;
  private final FlowSummaryCliProperties properties;

  public FlowSummaryCliRunner(
      FlowMemorySummarizerService summarizerService, FlowSummaryCliProperties properties) {
    this.summarizerService = summarizerService;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.getSessionId() == null
        || !StringUtils.hasText(properties.getProviderId())
        || !StringUtils.hasText(properties.getModelId())) {
      log.warn(
          "Flow summary CLI requires sessionId, providerId and modelId (received sessionId={}, providerId={}, modelId={})",
          properties.getSessionId(),
          properties.getProviderId(),
          properties.getModelId());
      return;
    }
    List<String> channels = properties.getChannels();
    summarizerService.forceSummarize(
        properties.getSessionId(), properties.getProviderId(), properties.getModelId(), channels);
    log.info(
        "Flow summary rebuild triggered via CLI for session {} (channels={}, providerId={}, modelId={})",
        properties.getSessionId(),
        channels.isEmpty() ? "[default]" : channels,
        properties.getProviderId(),
        properties.getModelId());
  }
}
