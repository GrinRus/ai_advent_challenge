package com.aiadvent.backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.api.StructuredSyncAnswer;
import com.aiadvent.backend.chat.api.StructuredSyncItem;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncStatus;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.memory.ChatSummarizationPreflightManager;
import com.aiadvent.backend.profile.service.ProfilePromptService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@ExtendWith(MockitoExtension.class)
class StructuredSyncServiceTest {

  @Mock private ChatProviderService chatProviderService;

  @Mock private ChatService chatService;

  @Mock private BeanOutputConverter<StructuredSyncResponse> outputConverter;

  @Mock private ChatSummarizationPreflightManager preflightManager;

  @Mock private ChatResearchToolBindingService researchToolBindingService;

  @Mock private ProfilePromptService profilePromptService;

  private StructuredSyncService structuredSyncService;

  @BeforeEach
  void setUp() {
    structuredSyncService =
        new StructuredSyncService(
            chatProviderService,
            chatService,
            outputConverter,
            new ObjectMapper(),
            preflightManager,
            researchToolBindingService,
            profilePromptService);
    lenient()
        .when(researchToolBindingService.resolve(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(ChatResearchToolBindingService.ResearchContext.empty());
  }

  @Test
  void buildRetryTemplateRespectsConfiguredStatuses() {
    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    ChatProvidersProperties.Retry retry = new ChatProvidersProperties.Retry();
    retry.setAttempts(4);
    retry.setInitialDelay(Duration.ofMillis(100));
    retry.setMultiplier(1.0);
    retry.setRetryableStatuses(List.of(429));
    provider.setRetry(retry);

    RetryTemplate template =
        ReflectionTestUtils.invokeMethod(structuredSyncService, "buildRetryTemplate", provider);

    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                template.execute(
                    context -> {
                      attempts.incrementAndGet();
                      throw WebClientResponseException.create(
                          HttpStatus.TOO_MANY_REQUESTS.value(),
                          "Too Many Requests",
                          HttpHeaders.EMPTY,
                          new byte[0],
                          StandardCharsets.UTF_8);
                    }))
        .isInstanceOf(WebClientResponseException.class);

    assertThat(attempts.get()).isEqualTo(retry.getAttempts());
  }

  @Test
  void buildRetryTemplateSkipsStatusesNotConfigured() {
    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    ChatProvidersProperties.Retry retry = new ChatProvidersProperties.Retry();
    retry.setAttempts(5);
    retry.setInitialDelay(Duration.ofMillis(50));
    retry.setMultiplier(2.0);
    retry.setRetryableStatuses(List.of(429));
    provider.setRetry(retry);

    RetryTemplate template =
        ReflectionTestUtils.invokeMethod(structuredSyncService, "buildRetryTemplate", provider);

    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                template.execute(
                    context -> {
                      attempts.incrementAndGet();
                      throw WebClientResponseException.create(
                          HttpStatus.BAD_GATEWAY.value(),
                          "Bad Gateway",
                          HttpHeaders.EMPTY,
                          new byte[0],
                          StandardCharsets.UTF_8);
                    }))
        .isInstanceOf(WebClientResponseException.class);

    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  void serializePayloadProducesJsonNode() {
    StructuredSyncProvider provider = new StructuredSyncProvider("ZHIPUAI", "glm-4.6");
    StructuredSyncItem item =
        new StructuredSyncItem("Main insight", "details", List.of("tag", "priority"));
    StructuredSyncAnswer answer =
        new StructuredSyncAnswer("summary", List.of(item), BigDecimal.valueOf(0.9));
    StructuredSyncResponse response =
        new StructuredSyncResponse(
            UUID.randomUUID(),
            StructuredSyncStatus.SUCCESS,
            provider,
            answer,
            List.of(),
            null,
            null,
            150L,
            null);

    JsonNode node =
        ReflectionTestUtils.invokeMethod(
            structuredSyncService, "serializePayload", response);

    assertThat(node).isNotNull();
    assertThat(node.get("provider").get("type").asText()).isEqualTo("ZHIPUAI");
    assertThat(node.get("answer").get("items")).hasSize(1);
  }
}
