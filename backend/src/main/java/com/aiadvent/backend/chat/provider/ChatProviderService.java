package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.util.StringUtils;

public class ChatProviderService {

  private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);
  private static final int COST_SCALE = 8;

  private final ChatProviderRegistry registry;
  private final Map<String, ChatProviderAdapter> adaptersById;

  public ChatProviderService(ChatProviderRegistry registry, List<ChatProviderAdapter> adapters) {
    this.registry = registry;
    this.adaptersById =
        adapters.stream().collect(Collectors.toUnmodifiableMap(ChatProviderAdapter::providerId, Function.identity()));
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

  public ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides) {
    ChatProviderAdapter adapter = adaptersById.get(selection.providerId());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + selection.providerId() + "'");
    }
    return adapter.buildOptions(selection, overrides);
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

  public UsageCostEstimate estimateUsageCost(ChatProviderSelection selection, Usage usage) {
    if (usage == null) {
      return UsageCostEstimate.empty();
    }

    ChatProvidersProperties.Model model =
        registry.requireModel(selection.providerId(), selection.modelId());
    ChatProvidersProperties.Pricing pricing = model.getPricing();

    Integer promptTokens = toInteger(usage.getPromptTokens());
    Integer completionTokens = toInteger(usage.getCompletionTokens());
    Integer totalTokens = toInteger(usage.getTotalTokens());
    if (totalTokens == null && promptTokens != null && completionTokens != null) {
      totalTokens = promptTokens + completionTokens;
    }

    BigDecimal inputRate = pricing != null ? pricing.getInputPer1KTokens() : null;
    BigDecimal outputRate = pricing != null ? pricing.getOutputPer1KTokens() : null;
    BigDecimal inputCost = computeCost(promptTokens, inputRate);
    BigDecimal outputCost = computeCost(completionTokens, outputRate);
    BigDecimal totalCost = sumCosts(inputCost, outputCost);
    String currency =
        pricing != null && StringUtils.hasText(pricing.getCurrency())
            ? pricing.getCurrency()
            : (inputCost != null || outputCost != null ? "USD" : null);

    return new UsageCostEstimate(
        promptTokens, completionTokens, totalTokens, inputCost, outputCost, totalCost, currency);
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
