package com.aiadvent.backend.flow;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class TestAgentInvocationOptionsFactory {

  private TestAgentInvocationOptionsFactory() {}

  public static AgentInvocationOptions minimal() {
    return minimal(ChatProviderType.OPENAI, "openai", "gpt-4o-mini");
  }

  public static AgentInvocationOptions minimal(
      ChatProviderType providerType, String providerId, String modelId) {
    return new AgentInvocationOptions(
        new AgentInvocationOptions.Provider(providerType, providerId, modelId, AgentInvocationOptions.InvocationMode.SYNC),
        new AgentInvocationOptions.Prompt(
            null,
            null,
            List.of(),
            new AgentInvocationOptions.GenerationDefaults(0.2, 0.9, 800)),
        new AgentInvocationOptions.MemoryPolicy(
            List.of("shared", "conversation"),
            14,
            100,
            AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
            AgentInvocationOptions.OverflowAction.TRIM_OLDEST),
        new AgentInvocationOptions.RetryPolicy(
            3,
            250L,
            2.0,
            List.of(429, 500, 503),
            30_000L,
            90_000L,
            100L),
        AgentInvocationOptions.AdvisorSettings.empty(),
        new AgentInvocationOptions.Tooling(List.of()),
        new AgentInvocationOptions.CostProfile(
            java.math.BigDecimal.valueOf(0.01),
            java.math.BigDecimal.valueOf(0.03),
            null,
            null,
            "USD"));
  }

  public static ObjectNode minimalJson(ObjectMapper mapper) {
    return minimalJson(mapper, ChatProviderType.OPENAI, "openai", "gpt-4o-mini");
  }

  public static ObjectNode minimalJson(
      ObjectMapper mapper, ChatProviderType providerType, String providerId, String modelId) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode provider = root.putObject("provider");
    provider.put("type", providerType.name());
    provider.put("id", providerId);
    provider.put("modelId", modelId);
    provider.put("mode", AgentInvocationOptions.InvocationMode.SYNC.name());

    ObjectNode prompt = root.putObject("prompt");
    prompt.putNull("templateId");
    prompt.putNull("system");
    prompt.putArray("variables");
    ObjectNode generation = prompt.putObject("generation");
    generation.put("temperature", 0.2);
    generation.put("topP", 0.9);
    generation.put("maxOutputTokens", 800);

    ObjectNode memoryPolicy = root.putObject("memoryPolicy");
    memoryPolicy.putArray("channels").add("shared").add("conversation");
    memoryPolicy.put("retentionDays", 14);
    memoryPolicy.put("maxEntries", 100);
    memoryPolicy.put("summarizationStrategy", AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY.name());
    memoryPolicy.put("overflowAction", AgentInvocationOptions.OverflowAction.TRIM_OLDEST.name());

    ObjectNode retryPolicy = root.putObject("retryPolicy");
    retryPolicy.put("maxAttempts", 3);
    retryPolicy.put("initialDelayMs", 250);
    retryPolicy.put("multiplier", 2.0);
    retryPolicy.putArray("retryableStatuses").add(429).add(500).add(503);
    retryPolicy.put("timeoutMs", 30_000);
    retryPolicy.put("overallDeadlineMs", 90_000);
    retryPolicy.put("jitterMs", 100);

    ObjectNode advisorSettings = root.putObject("advisorSettings");
    advisorSettings.putObject("telemetry").put("enabled", true);
    advisorSettings.putObject("audit").put("enabled", true).put("redactPii", true);
    advisorSettings.putObject("routing").put("enabled", false);

    root.putObject("tooling").putArray("bindings");

    ObjectNode costProfile = root.putObject("costProfile");
    costProfile.put("inputPer1KTokens", 0.01);
    costProfile.put("outputPer1KTokens", 0.03);
    costProfile.put("currency", "USD");

    return root;
  }
}

