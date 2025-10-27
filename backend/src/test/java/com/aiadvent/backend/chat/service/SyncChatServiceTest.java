package com.aiadvent.backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.memory.ChatSummarizationPreflightManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class SyncChatServiceTest {

  @Mock private ChatProviderService chatProviderService;

  @Mock private ChatService chatService;

  @Mock private ChatSummarizationPreflightManager preflightManager;

  @Mock private ChatResearchToolBindingService researchToolBindingService;

  @Mock private BeanOutputConverter<com.aiadvent.backend.chat.api.StructuredSyncResponse>
      structuredOutputConverter;

  private SyncChatService syncChatService;

  @BeforeEach
  void setUp() {
    syncChatService =
        new SyncChatService(
            chatProviderService,
            chatService,
            preflightManager,
            researchToolBindingService,
            structuredOutputConverter);
  }

  @Test
  void buildRetryTemplateRespectsConfiguredStatuses() {
    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    ChatProvidersProperties.Retry retry = new ChatProvidersProperties.Retry();
    retry.setAttempts(4);
    retry.setInitialDelay(Duration.ofMillis(50));
    retry.setMultiplier(1.0);
    retry.setRetryableStatuses(List.of(429));
    provider.setRetry(retry);

    RetryTemplate template =
        ReflectionTestUtils.invokeMethod(syncChatService, "buildRetryTemplate", provider);

    assertThat(template).isNotNull();
    assertThatThrownBy(
            () ->
                template.execute(
                    context -> {
                      throw WebClientResponseException.create(
                          HttpStatus.TOO_MANY_REQUESTS.value(),
                          "Too Many Requests",
                          HttpHeaders.EMPTY,
                          null,
                          null);
                    }))
        .isInstanceOf(WebClientResponseException.class);
  }

  @Test
  void sanitizeMessageRejectsBlankInput() {
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(syncChatService, "sanitizeMessage", "   "))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void sanitizeMessageReturnsTrimmedValue() {
    String result =
        ReflectionTestUtils.invokeMethod(syncChatService, "sanitizeMessage", "  Hello sync  ");
    assertThat(result).isEqualTo("Hello sync");
  }

  @Test
  void mapFailureConvertsWebClientException() {
    WebClientResponseException webClientError =
        WebClientResponseException.create(
            HttpStatus.BAD_GATEWAY.value(),
            "Bad Gateway",
            HttpHeaders.EMPTY,
            null,
            null);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    syncChatService, "mapFailure", webClientError))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.BAD_GATEWAY);
  }
}
