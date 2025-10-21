package com.aiadvent.backend.chat.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.chat.provider.model.UsageSource;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.EstimateRequest;
import com.aiadvent.backend.chat.token.TokenUsageMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

class ChatProviderServiceTest {

  private ChatProvidersProperties properties;

  @BeforeEach
  void setUp() {
    properties = new ChatProvidersProperties();
    properties.setDefaultProvider("test");

    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    provider.setType(ChatProviderType.OPENAI);
    provider.setDefaultModel("model");

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    model.setDisplayName("Test Model");
    model.getPricing().setInputPer1KTokens(new BigDecimal("0.0020"));
    model.getPricing().setOutputPer1KTokens(new BigDecimal("0.0040"));
    model.getUsage().setMode(ChatProvidersProperties.UsageMode.AUTO);

    provider.getModels().put("model", model);
    properties.getProviders().put("test", provider);
  }

  @Test
  void estimateUsageCostPrefersNativeMetadata() {
    TokenUsageEstimator estimator = mock(TokenUsageEstimator.class);
    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    ChatProviderService service =
        new ChatProviderService(new ChatProviderRegistry(properties), List.of(), estimator, metrics);

    Usage metadata = new DefaultUsage(12, 18, 30);
    UsageCostEstimate result =
        service.estimateUsageCost(
            new ChatProviderSelection("test", "model"), metadata, "prompt text", "completion text");

    assertThat(result.promptTokens()).isEqualTo(12);
    assertThat(result.completionTokens()).isEqualTo(18);
    assertThat(result.totalTokens()).isEqualTo(30);
    assertThat(result.source()).isEqualTo(UsageSource.NATIVE);
    assertThat(result.inputCost()).isEqualByComparingTo("0.00002400");
    assertThat(result.outputCost()).isEqualByComparingTo("0.00007200");
    assertThat(result.totalCost()).isEqualByComparingTo("0.00009600");
    verify(estimator, never()).estimate(any());
  }

  @Test
  void estimateUsageCostFallsBackWhenConfigured() {
    properties
        .getProviders()
        .get("test")
        .getModels()
        .get("model")
        .getUsage()
        .setMode(ChatProvidersProperties.UsageMode.FALLBACK);
    properties
        .getProviders()
        .get("test")
        .getModels()
        .get("model")
        .getUsage()
        .setFallbackTokenizer("cl100k_base");

    TokenUsageEstimator estimator = mock(TokenUsageEstimator.class);
    when(estimator.estimate(any()))
        .thenReturn(new Estimate(15, 25, 40, true, false));

    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    ChatProviderService service =
        new ChatProviderService(new ChatProviderRegistry(properties), List.of(), estimator, metrics);

    UsageCostEstimate result =
        service.estimateUsageCost(
            new ChatProviderSelection("test", "model"), null, "hello world", "fallback output");

    assertThat(result.promptTokens()).isEqualTo(15);
    assertThat(result.completionTokens()).isEqualTo(25);
    assertThat(result.totalTokens()).isEqualTo(40);
    assertThat(result.source()).isEqualTo(UsageSource.FALLBACK);
    assertThat(result.inputCost()).isEqualByComparingTo("0.00003000");
    assertThat(result.outputCost()).isEqualByComparingTo("0.00010000");
    assertThat(result.totalCost()).isEqualByComparingTo("0.00013000");

    ArgumentCaptor<EstimateRequest> requestCaptor = ArgumentCaptor.forClass(EstimateRequest.class);
    verify(estimator).estimate(requestCaptor.capture());
    EstimateRequest request = requestCaptor.getValue();
    assertThat(request.providerId()).isEqualTo("test");
    assertThat(request.modelId()).isEqualTo("model");
    assertThat(request.tokenizer()).isEqualTo("cl100k_base");
    assertThat(request.prompt()).isEqualTo("hello world");
    assertThat(request.completion()).isEqualTo("fallback output");
  }

  @Test
  void estimateUsageCostReturnsEmptyWhenNativeRequiredButMissing() {
    properties
        .getProviders()
        .get("test")
        .getModels()
        .get("model")
        .getUsage()
        .setMode(ChatProvidersProperties.UsageMode.NATIVE);

    TokenUsageEstimator estimator = mock(TokenUsageEstimator.class);
    TokenUsageMetrics metrics = new TokenUsageMetrics(new SimpleMeterRegistry());
    ChatProviderService service =
        new ChatProviderService(new ChatProviderRegistry(properties), List.of(), estimator, metrics);

    UsageCostEstimate result =
        service.estimateUsageCost(
            new ChatProviderSelection("test", "model"), null, "prompt text", "completion text");

    assertThat(result.promptTokens()).isNull();
    assertThat(result.source()).isEqualTo(UsageSource.UNKNOWN);
    verify(estimator, never()).estimate(any());
  }
}
