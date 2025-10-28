package com.aiadvent.backend.flow.agent.options;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AgentInvocationOptions {

  @JsonProperty("provider")
  private final Provider provider;

  @JsonProperty("prompt")
  private final Prompt prompt;

  @JsonProperty("memoryPolicy")
  private final MemoryPolicy memoryPolicy;

  @JsonProperty("retryPolicy")
  private final RetryPolicy retryPolicy;

  @JsonProperty("advisorSettings")
  private final AdvisorSettings advisorSettings;

  @JsonProperty("tooling")
  private final Tooling tooling;

  @JsonProperty("costProfile")
  private final CostProfile costProfile;

  private static final AgentInvocationOptions EMPTY =
      new AgentInvocationOptions(
          Provider.empty(),
          Prompt.empty(),
          MemoryPolicy.empty(),
          RetryPolicy.empty(),
          AdvisorSettings.empty(),
          Tooling.empty(),
          CostProfile.empty());

  @JsonCreator
  public AgentInvocationOptions(
      @JsonProperty("provider") Provider provider,
      @JsonProperty("prompt") Prompt prompt,
      @JsonProperty("memoryPolicy") MemoryPolicy memoryPolicy,
      @JsonProperty("retryPolicy") RetryPolicy retryPolicy,
      @JsonProperty("advisorSettings") AdvisorSettings advisorSettings,
      @JsonProperty("tooling") Tooling tooling,
      @JsonProperty("costProfile") CostProfile costProfile) {

    this.provider = provider != null ? provider : Provider.empty();
    this.prompt = prompt != null ? prompt : Prompt.empty();
    this.memoryPolicy = memoryPolicy != null ? memoryPolicy : MemoryPolicy.empty();
    this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.empty();
    this.advisorSettings = advisorSettings != null ? advisorSettings : AdvisorSettings.empty();
    this.tooling = tooling != null ? tooling : Tooling.empty();
    this.costProfile = costProfile != null ? costProfile : CostProfile.empty();
  }

  public static AgentInvocationOptions empty() {
    return EMPTY;
  }

  public Provider provider() {
    return provider;
  }

  public Prompt prompt() {
    return prompt;
  }

  public MemoryPolicy memoryPolicy() {
    return memoryPolicy;
  }

  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  public AdvisorSettings advisorSettings() {
    return advisorSettings;
  }

  public Tooling tooling() {
    return tooling;
  }

  public CostProfile costProfile() {
    return costProfile;
  }

  // Provider
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provider(
      ChatProviderType type,
      String id,
      String modelId,
      InvocationMode mode) {

    private static final Provider EMPTY = new Provider(null, null, null, InvocationMode.SYNC);

    @JsonCreator
    public Provider(
        @JsonProperty("type") ChatProviderType type,
        @JsonProperty("id") String id,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("mode") InvocationMode mode) {
      this.type = type;
      this.id = id;
      this.modelId = modelId;
      this.mode = mode != null ? mode : InvocationMode.SYNC;
    }

    public static Provider empty() {
      return EMPTY;
    }
  }

  public enum InvocationMode {
    SYNC,
    STREAM,
    STRUCTURED
  }

  // Prompt
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Prompt {
    private final String templateId;
    private final String system;
    private final List<PromptVariable> variables;
    private final GenerationDefaults generation;

    private static final Prompt EMPTY =
        new Prompt(null, null, List.of(), GenerationDefaults.empty());

    @JsonCreator
    public Prompt(
        @JsonProperty("templateId") String templateId,
        @JsonProperty("system") String system,
        @JsonProperty("variables") List<PromptVariable> variables,
        @JsonProperty("generation") GenerationDefaults generation) {
      this.templateId = templateId;
      this.system = system;
      this.variables =
          variables == null || variables.isEmpty()
              ? List.of()
              : List.copyOf(new ArrayList<>(variables));
      this.generation = generation != null ? generation : GenerationDefaults.empty();
    }

    public static Prompt empty() {
      return EMPTY;
    }

    public String templateId() {
      return templateId;
    }

    public String system() {
      return system;
    }

    public List<PromptVariable> variables() {
      return variables;
    }

    public GenerationDefaults generation() {
      return generation;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PromptVariable(
      String name, boolean required, String description, JsonNode schema) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class GenerationDefaults {
    private final Double temperature;
    private final Double topP;
    private final Integer maxOutputTokens;

    private static final GenerationDefaults EMPTY = new GenerationDefaults(null, null, null);

    @JsonCreator
    public GenerationDefaults(
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("topP") Double topP,
        @JsonProperty("maxOutputTokens") Integer maxOutputTokens) {
      this.temperature = temperature;
      this.topP = topP;
      this.maxOutputTokens = maxOutputTokens;
    }

    public static GenerationDefaults empty() {
      return EMPTY;
    }

    public Double temperature() {
      return temperature;
    }

    public Double topP() {
      return topP;
    }

    public Integer maxOutputTokens() {
      return maxOutputTokens;
    }

    @JsonIgnore
    public boolean isEmpty() {
      return temperature == null && topP == null && maxOutputTokens == null;
    }
  }

  // MemoryPolicy
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class MemoryPolicy {
    private final List<String> channels;
    private final Integer retentionDays;
    private final Integer maxEntries;
    private final SummarizationStrategy summarizationStrategy;
    private final OverflowAction overflowAction;

    private static final MemoryPolicy EMPTY =
        new MemoryPolicy(List.of(), null, null, SummarizationStrategy.NONE, OverflowAction.TRIM_OLDEST);

    @JsonCreator
    public MemoryPolicy(
        @JsonProperty("channels") List<String> channels,
        @JsonProperty("retentionDays") Integer retentionDays,
        @JsonProperty("maxEntries") Integer maxEntries,
        @JsonProperty("summarizationStrategy") SummarizationStrategy summarizationStrategy,
        @JsonProperty("overflowAction") OverflowAction overflowAction) {
      this.channels =
          channels == null || channels.isEmpty()
              ? List.of()
              : List.copyOf(new ArrayList<>(channels));
      this.retentionDays = retentionDays;
      this.maxEntries = maxEntries;
      this.summarizationStrategy =
          summarizationStrategy != null ? summarizationStrategy : SummarizationStrategy.NONE;
      this.overflowAction = overflowAction != null ? overflowAction : OverflowAction.TRIM_OLDEST;
    }

    public static MemoryPolicy empty() {
      return EMPTY;
    }

    public List<String> channels() {
      return channels;
    }

    public Integer retentionDays() {
      return retentionDays;
    }

    public Integer maxEntries() {
      return maxEntries;
    }

    public SummarizationStrategy summarizationStrategy() {
      return summarizationStrategy;
    }

    public OverflowAction overflowAction() {
      return overflowAction;
    }
  }

  public enum SummarizationStrategy {
    NONE,
    TAIL_WITH_SUMMARY,
    SUMMARY_ONLY
  }

  public enum OverflowAction {
    TRIM_OLDEST,
    REJECT_NEW
  }

  // RetryPolicy
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class RetryPolicy {
    private final Integer maxAttempts;
    private final Long initialDelayMs;
    private final Double multiplier;
    private final List<Integer> retryableStatuses;
    private final Long timeoutMs;
    private final Long overallDeadlineMs;
    private final Long jitterMs;

    private static final RetryPolicy EMPTY =
        new RetryPolicy(null, null, null, List.of(), null, null, null);

    @JsonCreator
    public RetryPolicy(
        @JsonProperty("maxAttempts") Integer maxAttempts,
        @JsonProperty("initialDelayMs") Long initialDelayMs,
        @JsonProperty("multiplier") Double multiplier,
        @JsonProperty("retryableStatuses") List<Integer> retryableStatuses,
        @JsonProperty("timeoutMs") Long timeoutMs,
        @JsonProperty("overallDeadlineMs") Long overallDeadlineMs,
        @JsonProperty("jitterMs") Long jitterMs) {
      this.maxAttempts = maxAttempts;
      this.initialDelayMs = initialDelayMs;
      this.multiplier = multiplier;
      this.retryableStatuses =
          retryableStatuses == null || retryableStatuses.isEmpty()
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(retryableStatuses));
      this.timeoutMs = timeoutMs;
      this.overallDeadlineMs = overallDeadlineMs;
      this.jitterMs = jitterMs;
    }

    public static RetryPolicy empty() {
      return EMPTY;
    }

    public Integer maxAttempts() {
      return maxAttempts;
    }

    public Long initialDelayMs() {
      return initialDelayMs;
    }

    public Double multiplier() {
      return multiplier;
    }

    public List<Integer> retryableStatuses() {
      return retryableStatuses;
    }

    public Long timeoutMs() {
      return timeoutMs;
    }

    public Long overallDeadlineMs() {
      return overallDeadlineMs;
    }

    public Long jitterMs() {
      return jitterMs;
    }
  }

  // AdvisorSettings
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class AdvisorSettings {
    private final AdvisorToggle telemetry;
    private final AuditSettings audit;
    private final RoutingSettings routing;

    private static final AdvisorSettings EMPTY =
        new AdvisorSettings(AdvisorToggle.disabled(), AuditSettings.disabled(), RoutingSettings.disabled());

    @JsonCreator
    public AdvisorSettings(
        @JsonProperty("telemetry") AdvisorToggle telemetry,
        @JsonProperty("audit") AuditSettings audit,
        @JsonProperty("routing") RoutingSettings routing) {
      this.telemetry = telemetry != null ? telemetry : AdvisorToggle.disabled();
      this.audit = audit != null ? audit : AuditSettings.disabled();
      this.routing = routing != null ? routing : RoutingSettings.disabled();
    }

    public static AdvisorSettings empty() {
      return EMPTY;
    }

    public AdvisorToggle telemetry() {
      return telemetry;
    }

    public AuditSettings audit() {
      return audit;
    }

    public RoutingSettings routing() {
      return routing;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdvisorToggle(boolean enabled) {

      public static AdvisorToggle disabled() {
        return new AdvisorToggle(false);
      }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditSettings(boolean enabled, boolean redactPii) {

      public static AuditSettings disabled() {
        return new AuditSettings(false, false);
      }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoutingSettings(boolean enabled, JsonNode parameters) {

      public static RoutingSettings disabled() {
        return new RoutingSettings(false, null);
      }
    }
  }

  // Tooling
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Tooling {
    private final List<ToolBinding> bindings;

    private static final Tooling EMPTY = new Tooling(List.of());

    @JsonCreator
    public Tooling(@JsonProperty("bindings") List<ToolBinding> bindings) {
      this.bindings =
          bindings == null || bindings.isEmpty()
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(bindings));
    }

    public static Tooling empty() {
      return EMPTY;
    }

    public List<ToolBinding> bindings() {
      return bindings;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class ToolBinding {
    private final String toolCode;
    private final Integer schemaVersion;
    private final ExecutionMode executionMode;
    private final JsonNode requestOverrides;
    private final JsonNode responseExpectations;

    @JsonCreator
    public ToolBinding(
        @JsonProperty("toolCode") String toolCode,
        @JsonProperty("schemaVersion") Integer schemaVersion,
        @JsonProperty("executionMode") ExecutionMode executionMode,
        @JsonProperty("requestOverrides") JsonNode requestOverrides,
        @JsonProperty("responseExpectations") JsonNode responseExpectations) {
      this.toolCode = toolCode;
      this.schemaVersion = schemaVersion;
      this.executionMode = executionMode != null ? executionMode : ExecutionMode.AUTO;
      this.requestOverrides = requestOverrides;
      this.responseExpectations = responseExpectations;
    }

    public String toolCode() {
      return toolCode;
    }

    public Integer schemaVersion() {
      return schemaVersion;
    }

    public ExecutionMode executionMode() {
      return executionMode;
    }

    public JsonNode requestOverrides() {
      return requestOverrides;
    }

    public JsonNode responseExpectations() {
      return responseExpectations;
    }
  }

  public enum ExecutionMode {
    AUTO,
    MANUAL,
    MANDATORY
  }

  // CostProfile
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class CostProfile {
    private final BigDecimal inputPer1KTokens;
    private final BigDecimal outputPer1KTokens;
    private final BigDecimal latencyFee;
    private final BigDecimal fixedFee;
    private final String currency;

    private static final CostProfile EMPTY =
        new CostProfile(null, null, null, null, null);

    @JsonCreator
    public CostProfile(
        @JsonProperty("inputPer1KTokens") BigDecimal inputPer1KTokens,
        @JsonProperty("outputPer1KTokens") BigDecimal outputPer1KTokens,
        @JsonProperty("latencyFee") BigDecimal latencyFee,
        @JsonProperty("fixedFee") BigDecimal fixedFee,
        @JsonProperty("currency") String currency) {
      this.inputPer1KTokens = inputPer1KTokens;
      this.outputPer1KTokens = outputPer1KTokens;
      this.latencyFee = latencyFee;
      this.fixedFee = fixedFee;
      this.currency = currency;
    }

    public static CostProfile empty() {
      return EMPTY;
    }

    public BigDecimal inputPer1KTokens() {
      return inputPer1KTokens;
    }

    public BigDecimal outputPer1KTokens() {
      return outputPer1KTokens;
    }

    public BigDecimal latencyFee() {
      return latencyFee;
    }

    public BigDecimal fixedFee() {
      return fixedFee;
    }

    public String currency() {
      return currency;
    }

    @JsonIgnore
    public boolean isEmpty() {
      return inputPer1KTokens == null
          && outputPer1KTokens == null
          && latencyFee == null
          && fixedFee == null
          && (currency == null || currency.isBlank());
    }
  }

  @Override
  public String toString() {
    return "AgentInvocationOptions{" + "provider=" + provider + ", tooling=" + tooling + '}';
  }

  @Override
  public int hashCode() {
    return Objects.hash(provider, prompt, memoryPolicy, retryPolicy, advisorSettings, tooling, costProfile);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof AgentInvocationOptions other)) {
      return false;
    }
    return Objects.equals(provider, other.provider)
        && Objects.equals(prompt, other.prompt)
        && Objects.equals(memoryPolicy, other.memoryPolicy)
        && Objects.equals(retryPolicy, other.retryPolicy)
        && Objects.equals(advisorSettings, other.advisorSettings)
        && Objects.equals(tooling, other.tooling)
        && Objects.equals(costProfile, other.costProfile);
  }
}
