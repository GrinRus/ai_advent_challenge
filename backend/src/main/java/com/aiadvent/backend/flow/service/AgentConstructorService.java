package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderRegistry;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.api.AgentConstructorPoliciesResponse;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewRequest;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse.DiffEntry;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse.PreviewCostEstimate;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse.ToolCoverage;
import com.aiadvent.backend.flow.api.AgentConstructorProvidersResponse;
import com.aiadvent.backend.flow.api.AgentConstructorProvidersResponse.Model;
import com.aiadvent.backend.flow.api.AgentConstructorProvidersResponse.Provider;
import com.aiadvent.backend.flow.api.AgentConstructorToolsResponse;
import com.aiadvent.backend.flow.api.AgentConstructorToolsResponse.SchemaVersion;
import com.aiadvent.backend.flow.api.AgentConstructorToolsResponse.Tool;
import com.aiadvent.backend.flow.api.AgentConstructorValidateRequest;
import com.aiadvent.backend.flow.api.AgentConstructorValidateResponse;
import com.aiadvent.backend.flow.api.ValidationIssue;
import com.aiadvent.backend.flow.api.ValidationIssue.IssueSeverity;
import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentConstructorService {

  private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);

  private final ChatProviderRegistry chatProviderRegistry;
  private final ToolDefinitionRepository toolDefinitionRepository;
  private final ObjectMapper objectMapper;

  public AgentConstructorService(
      ChatProviderRegistry chatProviderRegistry,
      ToolDefinitionRepository toolDefinitionRepository,
      ObjectMapper objectMapper) {
    this.chatProviderRegistry = chatProviderRegistry;
    this.toolDefinitionRepository = toolDefinitionRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public AgentConstructorProvidersResponse listProviders() {
    Map<String, ChatProvidersProperties.Provider> providers = chatProviderRegistry.providers();
    List<Provider> providerDtos =
        providers.entrySet().stream()
            .map(
                entry -> {
                  String providerId = entry.getKey();
                  ChatProvidersProperties.Provider provider = entry.getValue();
                  List<Model> models =
                      provider.getModels().entrySet().stream()
                          .map(modelEntry -> mapModel(modelEntry.getKey(), modelEntry.getValue()))
                          .toList();
                  return new Provider(
                      providerId,
                      provider.getType(),
                      provider.getDisplayName(),
                      provider.getBaseUrl(),
                      models);
                })
            .sorted(java.util.Comparator.comparing(Provider::displayName, java.util.Comparator.nullsLast(String::compareToIgnoreCase)))
            .toList();
    return new AgentConstructorProvidersResponse(providerDtos);
  }

  @Transactional(readOnly = true)
  public AgentConstructorToolsResponse listTools() {
    List<Tool> tools =
        toolDefinitionRepository.findAllByOrderByDisplayNameAsc().stream()
            .map(this::mapToolDefinition)
            .toList();
    return new AgentConstructorToolsResponse(tools);
  }

  public AgentConstructorPoliciesResponse listPolicies() {
    List<AgentConstructorPoliciesResponse.RetryPolicyPreset> retryPresets =
        List.of(
            new AgentConstructorPoliciesResponse.RetryPolicyPreset(
                "standard",
                "Standard (3 attempts, exponential backoff)",
                new AgentInvocationOptions.RetryPolicy(
                    3, 250L, 2.0, List.of(429, 500, 502, 503, 504), 30_000L, 90_000L, 100L)),
            new AgentConstructorPoliciesResponse.RetryPolicyPreset(
                "aggressive",
                "Aggressive (5 attempts, fast backoff)",
                new AgentInvocationOptions.RetryPolicy(
                    5, 150L, 1.5, List.of(429, 500, 502, 503, 504), 20_000L, 60_000L, 50L)));

    List<AgentConstructorPoliciesResponse.MemoryPolicyPreset> memoryPresets =
        List.of(
            new AgentConstructorPoliciesResponse.MemoryPolicyPreset(
                "short-term",
                "Short term (shared + conversation)",
                new AgentInvocationOptions.MemoryPolicy(
                    List.of("shared", "conversation"),
                    7,
                    75,
                    AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
                    AgentInvocationOptions.OverflowAction.TRIM_OLDEST)),
            new AgentConstructorPoliciesResponse.MemoryPolicyPreset(
                "persistent",
                "Persistent shared memory",
                new AgentInvocationOptions.MemoryPolicy(
                    List.of("shared"),
                    30,
                    250,
                    AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
                    AgentInvocationOptions.OverflowAction.TRIM_OLDEST)));

    List<AgentConstructorPoliciesResponse.AdvisorPolicyPreset> advisorPresets =
        List.of(
            new AgentConstructorPoliciesResponse.AdvisorPolicyPreset(
                "telemetry-and-audit",
                "Telemetry + audit (with PII redaction)",
                new AgentInvocationOptions.AdvisorSettings(
                    new AgentInvocationOptions.AdvisorSettings.AdvisorToggle(true),
                    new AgentInvocationOptions.AdvisorSettings.AuditSettings(true, true),
                    AgentInvocationOptions.AdvisorSettings.RoutingSettings.disabled())),
            new AgentConstructorPoliciesResponse.AdvisorPolicyPreset(
                "minimal",
                "Minimal advisors",
                AgentInvocationOptions.AdvisorSettings.empty()));

    return new AgentConstructorPoliciesResponse(retryPresets, memoryPresets, advisorPresets);
  }

  public AgentConstructorValidateResponse validate(AgentConstructorValidateRequest request) {
    if (request == null || request.options() == null) {
      throw new IllegalArgumentException("Agent invocation options must be provided");
    }
    AgentInvocationOptions options = request.options();
    List<ValidationIssue> issues = new ArrayList<>();

    validateProvider(options, issues);
    validatePrompt(options, issues);
    validateRetryPolicy(options, issues);
    validateTooling(options, issues);

    return new AgentConstructorValidateResponse(options, issues);
  }

  public AgentConstructorPreviewResponse preview(AgentConstructorPreviewRequest request) {
    if (request == null || request.proposed() == null) {
      throw new IllegalArgumentException("Preview request must include proposed options");
    }

    AgentInvocationOptions proposed = request.proposed();
    AgentInvocationOptions baseline = request.baseline();

    AgentConstructorValidateResponse validateResponse = validate(new AgentConstructorValidateRequest(proposed));
    List<ValidationIssue> warnings =
        validateResponse.issues().stream()
            .filter(issue -> issue.severity() != IssueSeverity.ERROR)
            .toList();

    List<DiffEntry> diffs = computeDiffs(baseline, proposed);
    PreviewCostEstimate costEstimate = estimateCost(proposed, request.promptSample());
    List<ToolCoverage> coverage = computeToolCoverage(proposed);

    return new AgentConstructorPreviewResponse(proposed, diffs, costEstimate, coverage, warnings);
  }

  private void validateProvider(AgentInvocationOptions options, List<ValidationIssue> issues) {
    AgentInvocationOptions.Provider provider = options.provider();
    if (provider == null || !StringUtils.hasText(provider.id())) {
      issues.add(new ValidationIssue("/provider/id", "Provider identifier is required", IssueSeverity.ERROR));
      return;
    }
    try {
      chatProviderRegistry.requireProvider(provider.id());
    } catch (RuntimeException exception) {
      issues.add(
          new ValidationIssue(
              "/provider/id",
              "Unknown provider '" + provider.id() + "': " + exception.getMessage(),
              IssueSeverity.ERROR));
      return;
    }

    if (!StringUtils.hasText(provider.modelId())) {
      issues.add(new ValidationIssue("/provider/modelId", "Model identifier is required", IssueSeverity.ERROR));
      return;
    }

    try {
      chatProviderRegistry.requireModel(provider.id(), provider.modelId());
    } catch (RuntimeException exception) {
      issues.add(
          new ValidationIssue(
              "/provider/modelId",
              "Invalid model '" + provider.modelId() + "': " + exception.getMessage(),
              IssueSeverity.ERROR));
    }
  }

  private void validatePrompt(AgentInvocationOptions options, List<ValidationIssue> issues) {
    AgentInvocationOptions.Prompt prompt = options.prompt();
    if (prompt == null) {
      issues.add(new ValidationIssue("/prompt", "Prompt configuration must be provided", IssueSeverity.ERROR));
      return;
    }
    var generation = prompt.generation();
    if (generation != null) {
      if (generation.temperature() != null && (generation.temperature() < 0 || generation.temperature() > 2)) {
        issues.add(
            new ValidationIssue(
                "/prompt/generation/temperature",
                "Temperature must be between 0 and 2",
                IssueSeverity.ERROR));
      }
      if (generation.topP() != null && (generation.topP() <= 0 || generation.topP() > 1)) {
        issues.add(
            new ValidationIssue(
                "/prompt/generation/topP",
                "topP must be in range (0, 1]",
                IssueSeverity.ERROR));
      }
      if (generation.maxOutputTokens() != null && generation.maxOutputTokens() <= 0) {
        issues.add(
            new ValidationIssue(
                "/prompt/generation/maxOutputTokens",
                "maxOutputTokens must be positive",
                IssueSeverity.ERROR));
      }
    }
  }

  private void validateRetryPolicy(AgentInvocationOptions options, List<ValidationIssue> issues) {
    AgentInvocationOptions.RetryPolicy retryPolicy = options.retryPolicy();
    if (retryPolicy == null) {
      return;
    }
    if (retryPolicy.maxAttempts() != null && retryPolicy.maxAttempts() < 1) {
      issues.add(
          new ValidationIssue(
              "/retryPolicy/maxAttempts", "maxAttempts must be >= 1", IssueSeverity.ERROR));
    }
    if (retryPolicy.initialDelayMs() != null && retryPolicy.initialDelayMs() < 0) {
      issues.add(
          new ValidationIssue(
              "/retryPolicy/initialDelayMs", "initialDelayMs must be >= 0", IssueSeverity.ERROR));
    }
    if (retryPolicy.multiplier() != null && retryPolicy.multiplier() < 1.0) {
      issues.add(
          new ValidationIssue(
              "/retryPolicy/multiplier", "multiplier must be >= 1.0", IssueSeverity.WARNING));
    }
    if (retryPolicy.timeoutMs() != null && retryPolicy.timeoutMs() <= 0) {
      issues.add(
          new ValidationIssue(
              "/retryPolicy/timeoutMs", "timeoutMs must be positive", IssueSeverity.ERROR));
    }
    if (retryPolicy.overallDeadlineMs() != null
        && retryPolicy.timeoutMs() != null
        && retryPolicy.overallDeadlineMs() < retryPolicy.timeoutMs()) {
      issues.add(
          new ValidationIssue(
              "/retryPolicy/overallDeadlineMs",
              "overallDeadlineMs is less than timeoutMs",
              IssueSeverity.WARNING));
    }
  }

  private void validateTooling(AgentInvocationOptions options, List<ValidationIssue> issues) {
    AgentInvocationOptions.Tooling tooling = options.tooling();
    if (tooling == null || tooling.bindings().isEmpty()) {
      return;
    }

    Map<String, ToolDefinition> toolsByCode =
        toolDefinitionRepository.findAll().stream()
            .collect(Collectors.toMap(td -> td.getCode().toLowerCase(), Function.identity()));

    for (int i = 0; i < tooling.bindings().size(); i++) {
      AgentInvocationOptions.ToolBinding binding = tooling.bindings().get(i);
      String code = binding.toolCode();
      if (!StringUtils.hasText(code)) {
        issues.add(
            new ValidationIssue(
                "/tooling/bindings/" + i + "/toolCode",
                "toolCode must not be blank",
                IssueSeverity.ERROR));
        continue;
      }
      ToolDefinition definition = toolsByCode.get(code.toLowerCase());
      if (definition == null) {
        issues.add(
            new ValidationIssue(
                "/tooling/bindings/" + i + "/toolCode",
                "Tool '" + code + "' is not registered",
                IssueSeverity.ERROR));
        continue;
      }
      if (binding.schemaVersion() != null) {
        ToolSchemaVersion schemaVersion = definition.getSchemaVersion();
        if (schemaVersion == null || schemaVersion.getVersion() != binding.schemaVersion()) {
          issues.add(
              new ValidationIssue(
                  "/tooling/bindings/" + i + "/schemaVersion",
                  "Schema version mismatch for tool '" + code + "'",
                  IssueSeverity.WARNING));
        }
      }
    }
  }

  private List<DiffEntry> computeDiffs(
      AgentInvocationOptions baseline, AgentInvocationOptions proposed) {
    if (baseline == null) {
      return List.of();
    }
    JsonNode baselineNode = objectMapper.valueToTree(baseline);
    JsonNode proposedNode = objectMapper.valueToTree(proposed);
    List<DiffEntry> diffs = new ArrayList<>();
    collectDiffs("", baselineNode, proposedNode, diffs);
    return diffs;
  }

  private void collectDiffs(String path, JsonNode baseline, JsonNode proposed, List<DiffEntry> diffs) {
    if (baseline == null && proposed == null) {
      return;
    }
    if (baseline == null || proposed == null) {
      diffs.add(new DiffEntry(path, baseline, proposed));
      return;
    }
    if (baseline.equals(proposed)) {
      return;
    }
    if (baseline.isObject() && proposed.isObject()) {
      // breadth-first comparison to keep deterministic order
      Deque<String> fieldNames = new ArrayDeque<>();
      baseline.fieldNames().forEachRemaining(fieldNames::add);
      proposed.fieldNames().forEachRemaining(fieldNames::add);
      fieldNames.stream().distinct().sorted().forEach(field -> {
        String nextPath = path + "/" + field;
        collectDiffs(nextPath, baseline.get(field), proposed.get(field), diffs);
      });
      return;
    }
    diffs.add(new DiffEntry(path, baseline, proposed));
  }

  private PreviewCostEstimate estimateCost(AgentInvocationOptions options, String promptSample) {
    AgentInvocationOptions.CostProfile costProfile = options.costProfile();
    BigDecimal inputRate = costProfile != null ? costProfile.inputPer1KTokens() : null;
    BigDecimal outputRate = costProfile != null ? costProfile.outputPer1KTokens() : null;
    String currency =
        costProfile != null && StringUtils.hasText(costProfile.currency())
            ? costProfile.currency()
            : "USD";
    long promptTokens = estimatePromptTokens(promptSample);
    Integer maxOutput = options.prompt().generation().maxOutputTokens();
    long completionTokens = maxOutput != null && maxOutput > 0 ? maxOutput : 512L;

    BigDecimal inputCost =
        inputRate != null
            ? inputRate.multiply(BigDecimal.valueOf(promptTokens)).divide(ONE_THOUSAND, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    BigDecimal outputCost =
        outputRate != null
            ? outputRate
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(ONE_THOUSAND, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    BigDecimal totalCost = inputCost.add(outputCost);

    AgentInvocationOptions.RetryPolicy retryPolicy = options.retryPolicy();
    Long latencyMs = null;
    if (retryPolicy != null) {
      latencyMs = retryPolicy.timeoutMs();
      if (latencyMs == null && retryPolicy.maxAttempts() != null && retryPolicy.initialDelayMs() != null) {
        latencyMs = retryPolicy.initialDelayMs() * retryPolicy.maxAttempts();
      }
    }

    return new PreviewCostEstimate(
        promptTokens, completionTokens, totalCost, currency, latencyMs);
  }

  private long estimatePromptTokens(String promptSample) {
    if (!StringUtils.hasText(promptSample)) {
      return 128L;
    }
    int codePoints = promptSample.trim().codePointCount(0, promptSample.trim().length());
    return Math.max(32L, Math.round(codePoints / 4.0d));
  }

  private List<ToolCoverage> computeToolCoverage(AgentInvocationOptions options) {
    AgentInvocationOptions.Tooling tooling = options.tooling();
    if (tooling == null || tooling.bindings().isEmpty()) {
      return List.of();
    }
    Map<String, ToolDefinition> toolsByCode =
        toolDefinitionRepository.findAll().stream()
            .collect(Collectors.toMap(td -> td.getCode().toLowerCase(), Function.identity()));

    List<ToolCoverage> coverage = new ArrayList<>();
    for (AgentInvocationOptions.ToolBinding binding : tooling.bindings()) {
      if (!StringUtils.hasText(binding.toolCode())) {
        coverage.add(new ToolCoverage(null, false, "Tool code is not defined"));
        continue;
      }
      ToolDefinition definition = toolsByCode.get(binding.toolCode().toLowerCase());
      if (definition == null) {
        coverage.add(
            new ToolCoverage(
                binding.toolCode(),
                false,
                "Tool '" + binding.toolCode() + "' is not available in the catalog"));
      } else {
        coverage.add(new ToolCoverage(binding.toolCode(), true, null));
      }
    }
    return coverage;
  }

  private Tool mapToolDefinition(ToolDefinition definition) {
    ToolSchemaVersion schemaVersion = definition.getSchemaVersion();
    SchemaVersion schemaDto =
        schemaVersion == null
            ? null
            : new SchemaVersion(
                schemaVersion.getId(),
                schemaVersion.getVersion(),
                schemaVersion.getSchemaChecksum(),
                schemaVersion.getMcpServer(),
                schemaVersion.getMcpToolName(),
                schemaVersion.getTransport(),
                schemaVersion.getAuthScope());

    return new Tool(
        definition.getId(),
        definition.getCode(),
        definition.getDisplayName(),
        definition.getDescription(),
        definition.getProviderHint(),
        definition.getCallType(),
        definition.getTags(),
        definition.getCapabilities(),
        definition.getCostHint(),
        definition.getIconUrl(),
        definition.getDefaultTimeoutMs(),
        schemaDto);
  }

  private Model mapModel(String modelId, ChatProvidersProperties.Model model) {
    List<String> tags =
        Optional.ofNullable(model.getTier())
            .filter(StringUtils::hasText)
            .map(value -> List.of(value))
            .orElse(List.of());

    return new Model(
        modelId,
        model.getDisplayName(),
        model.isSyncEnabled(),
        model.isStreamingEnabled(),
        model.isStructuredEnabled(),
        model.getContextWindow(),
        model.getMaxOutputTokens(),
        tags);
  }
}
