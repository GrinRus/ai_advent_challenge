package com.aiadvent.backend.flow.telemetry;

import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Centralizes telemetry and lightweight audit logging for constructor flows (agent catalog and flow
 * blueprint editor).
 */
@Component
public class ConstructorTelemetryService {

  private static final Logger auditLogger = LoggerFactory.getLogger("ConstructorAudit");

  private static final String DOMAIN_FLOW_BLUEPRINT = "flow_blueprint";
  private static final String DOMAIN_AGENT_DEFINITION = "agent_definition";
  private static final String DOMAIN_AGENT_VERSION = "agent_version";

  private final MeterRegistry meterRegistry;

  public ConstructorTelemetryService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordFlowBlueprintSave(String action, FlowDefinition definition, String actor) {
    incrementCounter(
        "constructor_flow_blueprint_saves_total", Tags.of("action", safeTagValue(action)));
    incrementCounter(
        "constructor_user_events_total",
        Tags.of("event", safeMetricEvent("flow_blueprint", action)));
    auditLogger.info(
        "flow_blueprint_save action={} definitionId={} version={} actor={}",
        safeLogValue(action),
        definition != null ? definition.getId() : null,
        definition != null ? definition.getVersion() : null,
        safeActor(actor));
  }

  public void recordAgentDefinitionSave(String action, AgentDefinition definition, String actor) {
    incrementCounter(
        "constructor_agent_definition_saves_total", Tags.of("action", safeTagValue(action)));
    incrementCounter(
        "constructor_user_events_total",
        Tags.of("event", safeMetricEvent("agent_definition", action)));
    auditLogger.info(
        "agent_definition_save action={} definitionId={} identifier={} actor={}",
        safeLogValue(action),
        definition != null ? definition.getId() : null,
        definition != null ? definition.getIdentifier() : null,
        safeActor(actor));
  }

  public void recordAgentVersionSave(String action, AgentVersion version, String actor) {
    incrementCounter(
        "constructor_agent_version_saves_total", Tags.of("action", safeTagValue(action)));
    incrementCounter(
        "constructor_user_events_total",
        Tags.of("event", safeMetricEvent("agent_version", action)));
    auditLogger.info(
        "agent_version_save action={} versionId={} definitionId={} version={} actor={}",
        safeLogValue(action),
        version != null ? version.getId() : null,
        version != null && version.getAgentDefinition() != null
            ? version.getAgentDefinition().getId()
            : null,
        version != null ? version.getVersion() : null,
        safeActor(actor));
  }

  public void recordValidationError(String domain, String stage, String actor, Throwable error) {
    incrementCounter(
        "constructor_validation_errors_total",
        Tags.of("domain", safeTagValue(domain), "stage", safeTagValue(stage)));
    incrementCounter(
        "constructor_user_events_total",
        Tags.of("event", safeMetricEvent(domain, stage + "_validation_error")));
    auditLogger.warn(
        "constructor_validation_error domain={} stage={} actor={} message={}",
        safeLogValue(domain),
        safeLogValue(stage),
        safeActor(actor),
        error != null ? error.getMessage() : "unknown",
        error);
  }

  private void incrementCounter(String name, Tags tags) {
    Counter.builder(name).tags(tags).register(meterRegistry).increment();
  }

  private String safeTagValue(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "unknown";
    }
    String normalized =
        raw
            .trim()
            .replaceAll("[^A-Za-z0-9_\\-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    if (!StringUtils.hasText(normalized)) {
      return "unknown";
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private String safeMetricEvent(String domain, String action) {
    return safeTagValue(domain) + "_" + safeTagValue(action);
  }

  private String safeLogValue(String raw) {
    return StringUtils.hasText(raw) ? raw.trim() : "unknown";
  }

  private String safeActor(String actor) {
    return safeLogValue(actor);
  }
}
