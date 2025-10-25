package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.chat.provider.model.UsageSource;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.util.StringUtils;

public class ChatProviderService {

  private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);
  private static final int COST_SCALE = 8;

  private final ChatProviderRegistry registry;
  private final Map<String, ChatProviderAdapter> adaptersById;
  private final TokenUsageEstimator tokenUsageEstimator;
  private final TokenUsageMetrics tokenUsageMetrics;

  public ChatProviderService(
      ChatProviderRegistry registry,
      List<ChatProviderAdapter> adapters,
      TokenUsageEstimator tokenUsageEstimator,
      TokenUsageMetrics tokenUsageMetrics) {
    this.registry = registry;
    this.adaptersById =
        adapters.stream().collect(Collectors.toUnmodifiableMap(ChatProviderAdapter::providerId, Function.identity()));
    this.tokenUsageEstimator = tokenUsageEstimator;
    this.tokenUsageMetrics = tokenUsageMetrics;
  }

  public ChatProviderSelection resolveSelection(String provider, String model) {
    return registry.resolveSelection(provider, model);
  }

  public ChatClient chatClient(String providerId) {
    ChatProviderAdapter adapter = adaptersById.get(providerId);
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + providerId + "'");
    }
    return adapter.chatClient();
  }

  public ChatClient statelessChatClient(String providerId) {
    ChatProviderAdapter adapter = adaptersById.get(providerId);
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + providerId + "'");
    }
    return adapter.statelessChatClient();
  }

  public ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides) {
    ChatProviderAdapter adapter = adaptersById.get(selection.providerId());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + selection.providerId() + "'");
    }
    return adapter.buildOptions(selection, overrides);
  }

  public ChatOptions buildStreamingOptions(
      ChatProviderSelection selection, ChatRequestOverrides overrides) {
    ChatProviderAdapter adapter = adaptersById.get(selection.providerId());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + selection.providerId() + "'");
    }
    return adapter.buildStreamingOptions(selection, overrides);
  }

  public ChatOptions buildStructuredOptions(
      ChatProviderSelection selection,
      ChatRequestOverrides overrides,
      BeanOutputConverter<?> outputConverter) {
    ChatProviderAdapter adapter = adaptersById.get(selection.providerId());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + selection.providerId() + "'");
    }
    return adapter.buildStructuredOptions(selection, overrides, outputConverter);
  }

  public ChatResponse chatSyncWithOverrides(
      ChatProviderSelection selection,
      String systemPrompt,
      List<String> additionalSystemMessages,
      Map<String, Object> advisorParams,
      String userMessage,
      ChatRequestOverrides overrides) {
    ChatOptions options = buildOptions(selection, overrides != null ? overrides : ChatRequestOverrides.empty());
    var promptSpec = chatClient(selection.providerId()).prompt();
    if (StringUtils.hasText(systemPrompt)) {
      promptSpec.system(systemPrompt);
    }
    if (additionalSystemMessages != null) {
      additionalSystemMessages.stream()
          .filter(StringUtils::hasText)
          .map(message -> SystemMessage.builder().text(message).build())
          .forEach(promptSpec::messages);
    }
    if (advisorParams != null && !advisorParams.isEmpty()) {
      promptSpec.advisors(advisors -> advisorParams.forEach(advisors::param));
    }
    if (!StringUtils.hasText(userMessage)) {
      throw new IllegalArgumentException("userMessage must not be blank");
    }
    return promptSpec.user(userMessage).options(options).call().chatResponse();
  }

  public ChatProvidersProperties.Provider provider(String providerId) {
    return registry.requireProvider(providerId);
  }

  public ChatProvidersProperties.Model model(String providerId, String modelId) {
    return registry.requireModel(providerId, modelId);
  }

  public String defaultProvider() {
    return registry.defaultProvider();
  }

  public Map<String, ChatProvidersProperties.Provider> providers() {
    return Collections.unmodifiableMap(registry.providers());
  }

  public boolean supportsStreaming(ChatProviderSelection selection) {
    return registry.supportsStreaming(selection.providerId(), selection.modelId());
  }

  public boolean supportsSync(ChatProviderSelection selection) {
    return registry.supportsSync(selection.providerId(), selection.modelId());
  }

  public boolean supportsStructured(ChatProviderSelection selection) {
    return registry.supportsStructured(selection.providerId(), selection.modelId());
  }

  public UsageCostEstimate estimateUsageCost(
      ChatProviderSelection selection,
      Usage usageMetadata,
      String promptText,
      String completionText) {
    ChatProvidersProperties.Model model =
        registry.requireModel(selection.providerId(), selection.modelId());
    ChatProvidersProperties.Usage usageConfig =
        model.getUsage() != null ? model.getUsage() : new ChatProvidersProperties.Usage();

    Integer promptTokens = null;
    Integer completionTokens = null;
    Integer totalTokens = null;
    UsageSource usageSource = UsageSource.UNKNOWN;

    Integer nativePromptTokens = null;
    Integer nativeCompletionTokens = null;
    Integer nativeTotalTokens = null;

    boolean metadataAvailable =
        usageMetadata != null
            && (usageMetadata.getPromptTokens() != null
                || usageMetadata.getCompletionTokens() != null
                || usageMetadata.getTotalTokens() != null);

    if (metadataAvailable && usageConfig.getMode() != ChatProvidersProperties.UsageMode.FALLBACK) {
      promptTokens = toInteger(usageMetadata.getPromptTokens());
      completionTokens = toInteger(usageMetadata.getCompletionTokens());
      totalTokens = toInteger(usageMetadata.getTotalTokens());
      if (totalTokens == null && promptTokens != null && completionTokens != null) {
        totalTokens = promptTokens + completionTokens;
      }
      nativePromptTokens = promptTokens;
      nativeCompletionTokens = completionTokens;
      nativeTotalTokens = totalTokens;
      usageSource = UsageSource.NATIVE;
    }

    boolean shouldFallback =
        usageConfig.getMode() == ChatProvidersProperties.UsageMode.FALLBACK
            || (!metadataAvailable
                && usageConfig.getMode() != ChatProvidersProperties.UsageMode.NATIVE);

    if (shouldFallback && tokenUsageEstimator != null) {
      String tokenizer = usageConfig.getFallbackTokenizer();
      TokenUsageEstimator.Estimate fallbackEstimate =
          tokenUsageEstimator.estimate(
              new TokenUsageEstimator.EstimateRequest(
                  selection.providerId(), selection.modelId(), tokenizer, promptText, completionText));
      if (fallbackEstimate != null && fallbackEstimate.hasUsage()) {
        promptTokens = fallbackEstimate.promptTokens();
        completionTokens = fallbackEstimate.completionTokens();
        totalTokens = fallbackEstimate.totalTokens();
        usageSource = UsageSource.FALLBACK;
      }
    }

    if (totalTokens == null && promptTokens != null && completionTokens != null) {
      totalTokens = promptTokens + completionTokens;
    }

    ChatProvidersProperties.Pricing pricing = model.getPricing();
    BigDecimal inputRate = pricing != null ? pricing.getInputPer1KTokens() : null;
    BigDecimal outputRate = pricing != null ? pricing.getOutputPer1KTokens() : null;
    BigDecimal inputCost = computeCost(promptTokens, inputRate);
    BigDecimal outputCost = computeCost(completionTokens, outputRate);
    BigDecimal totalCost = sumCosts(inputCost, outputCost);
    String currency =
        pricing != null && StringUtils.hasText(pricing.getCurrency())
            ? pricing.getCurrency()
            : (inputCost != null || outputCost != null ? "USD" : null);

    if (promptTokens == null && completionTokens == null && totalTokens == null) {
      return UsageCostEstimate.empty();
    }

    recordUsageMetrics(
        selection.providerId(),
        selection.modelId(),
        usageSource,
        totalTokens,
        nativeTotalTokens,
        promptTokens,
        completionTokens,
        nativePromptTokens,
        nativeCompletionTokens);

    return new UsageCostEstimate(
        promptTokens,
        completionTokens,
        totalTokens,
        inputCost,
        outputCost,
        totalCost,
        currency,
        usageSource);
  }

  private void recordUsageMetrics(
      String providerId,
      String modelId,
      UsageSource usageSource,
      Integer fallbackTotalTokens,
      Integer nativeTotalTokens,
      Integer fallbackPromptTokens,
      Integer fallbackCompletionTokens,
      Integer nativePromptTokens,
      Integer nativeCompletionTokens) {
    if (tokenUsageMetrics == null) {
      return;
    }
    if (usageSource == UsageSource.NATIVE) {
      tokenUsageMetrics.recordNativeUsage(providerId, modelId, nativeTotalTokens);
    } else if (usageSource == UsageSource.FALLBACK) {
      tokenUsageMetrics.recordFallbackUsage(providerId, modelId, fallbackTotalTokens);
      tokenUsageMetrics.recordFallbackDelta(providerId, modelId, "total", fallbackTotalTokens, nativeTotalTokens);
      tokenUsageMetrics.recordFallbackDelta(providerId, modelId, "prompt", fallbackPromptTokens, nativePromptTokens);
      tokenUsageMetrics.recordFallbackDelta(providerId, modelId, "completion", fallbackCompletionTokens, nativeCompletionTokens);
    }
  }

  private Integer toInteger(Number value) {
    if (value == null) {
      return null;
    }
    return Math.toIntExact(value.longValue());
  }

  private BigDecimal computeCost(Integer tokens, BigDecimal ratePer1KTokens) {
    if (tokens == null || ratePer1KTokens == null) {
      return null;
    }
    return ratePer1KTokens
        .multiply(BigDecimal.valueOf(tokens.longValue()))
        .divide(ONE_THOUSAND, COST_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal sumCosts(BigDecimal first, BigDecimal second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return first.add(second).setScale(COST_SCALE, RoundingMode.HALF_UP);
  }
}
