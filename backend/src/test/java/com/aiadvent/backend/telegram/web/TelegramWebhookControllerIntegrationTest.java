package com.aiadvent.backend.telegram.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.service.ChatResearchToolBindingService;
import com.aiadvent.backend.chat.service.ConversationContext;
import com.aiadvent.backend.chat.service.SyncChatService;
import com.aiadvent.backend.chat.service.SyncChatService.SyncChatResult;
import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import com.aiadvent.backend.telegram.service.TelegramChatState;
import com.aiadvent.backend.telegram.service.TelegramChatService;
import com.aiadvent.backend.telegram.service.TelegramChatStateStore;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.service.ProfileLookupKey;
import com.aiadvent.backend.profile.service.UserProfileDocument;
import com.aiadvent.backend.profile.service.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;
import com.aiadvent.backend.profile.config.ProfileDevAuthProperties;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@WebMvcTest(controllers = TelegramWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
  TelegramChatService.class,
  TelegramChatStateStore.class,
  TelegramWebhookControllerIntegrationTest.TelegramTestConfig.class
})
@TestPropertySource(
    properties = {
      "app.telegram.enabled=true",
      "app.telegram.bot.token=test-token",
      "app.telegram.bot.username=test_bot",
      "app.telegram.webhook.external-url=https://example.com/telegram",
      "app.telegram.webhook.secret-token=test-secret"
    })
class TelegramWebhookControllerIntegrationTest {

  private static final long CHAT_ID = 123456789L;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TelegramChatStateStore stateStore;
  @Autowired private TelegramBotProperties telegramBotProperties;
  @Autowired private TelegramChatService telegramChatService;

  @MockBean private SyncChatService syncChatService;
  @MockBean private ChatProviderService chatProviderService;
  @MockBean private ChatResearchToolBindingService chatResearchToolBindingService;
  @MockBean private TelegramWebhookBotAdapter telegramWebhookBotAdapter;
  @MockBean private OpenAiAudioTranscriptionModel openAiAudioTranscriptionModel;
  @MockBean private UserProfileService userProfileService;

  @BeforeEach
  void setUp() throws TelegramApiException {
    telegramBotProperties.setEnabled(true);
    telegramBotProperties.getBot().setToken("test-token");
    telegramBotProperties.getBot().setUsername("test_bot");
    telegramBotProperties.getWebhook().setExternalUrl("https://example.com/telegram");
    telegramBotProperties.getWebhook().setSecretToken("test-secret");
    when(chatProviderService.resolveSelection(null, null))
        .thenReturn(new ChatProviderSelection("openai", "gpt-4o-mini"));
    doReturn(new Message()).when(telegramWebhookBotAdapter).execute(any(SendMessage.class));
    doReturn(null).when(telegramWebhookBotAdapter).execute(isA(AnswerCallbackQuery.class));
    doAnswer(
            invocation -> {
              Update update = invocation.getArgument(0);
              telegramChatService.handle(update);
              return null;
            })
        .when(telegramWebhookBotAdapter)
        .onWebhookUpdateReceived(any(Update.class));
    stateStore.reset(CHAT_ID);
    UserProfileDocument profileDocument =
        new UserProfileDocument(
            UUID.randomUUID(),
            "telegram",
            Long.toString(CHAT_ID),
            "Test User",
            "ru",
            "UTC",
            UserProfile.CommunicationMode.TEXT,
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            0L);
    when(userProfileService.resolveProfile(any(ProfileLookupKey.class))).thenReturn(profileDocument);
  }

  @Test
  void textMessageUpdateTriggersSyncFlow() throws Exception {
    Update update = createTextUpdate("Привет");

    UUID sessionId = UUID.randomUUID();
    ChatSyncResponse syncResponse =
        new ChatSyncResponse(
            UUID.randomUUID(),
            "Готово!",
            new StructuredSyncProvider("OPENAI", "gpt-4o-mini"),
            null,
            null,
            new StructuredSyncUsageStats(120, 200, 320),
            new UsageCostDetails(
                new BigDecimal("0.0015"), new BigDecimal("0.0025"), new BigDecimal("0.0040"), "USD"),
            420L,
            Instant.parse("2024-06-01T10:15:30Z"));

    ConversationContext context =
        new ConversationContext(sessionId, true, UUID.randomUUID());
    when(syncChatService.sync(any()))
        .thenReturn(new SyncChatResult(context, syncResponse));

    mockMvc
        .perform(
            post("/telegram/update")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk());

    ArgumentCaptor<ChatSyncRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatSyncRequest.class);
    verify(syncChatService, timeout(2000)).sync(requestCaptor.capture());

    ChatSyncRequest capturedRequest = requestCaptor.getValue();
    assertThat(capturedRequest.message()).isEqualTo("Привет");
    assertThat(capturedRequest.provider()).isEqualTo("openai");
    assertThat(capturedRequest.model()).isEqualTo("gpt-4o-mini");
    assertThat(capturedRequest.mode()).isEqualTo("default");
    assertThat(capturedRequest.requestedToolCodes()).isNull();
    assertThat(capturedRequest.options()).isNull();

    ArgumentCaptor<SendMessage> sendCaptor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramWebhookBotAdapter, timeout(2000).times(3))
        .execute(sendCaptor.capture());

    List<SendMessage> sentMessages = sendCaptor.getAllValues();
    assertThat(sentMessages).hasSize(3);
    assertThat(sentMessages.get(0).getText())
        .contains("Отправил запрос")
        .contains("openai/gpt-4o-mini");
    assertThat(sentMessages.get(1).getText()).isEqualTo("Готово!");
    assertThat(sentMessages.get(2).getText())
        .startsWith("Ответ готов:")
        .contains("• Провайдер: openai")
        .contains("• Модель: gpt-4o-mini");

    awaitStateWithSession(sessionId);
  }

  private Update createTextUpdate(String text) {
    Update update = new Update();
    Message message = new Message();
    Chat chat = new Chat();
    chat.setId(CHAT_ID);
    message.setMessageId(42);
    message.setChat(chat);
    message.setText(text);
    message.setDate((int) (Instant.now().getEpochSecond()));
    User user = new User();
    user.setId(987654321L);
    user.setIsBot(false);
    user.setFirstName("Test");
    message.setFrom(user);
    update.setUpdateId(1);
    update.setMessage(message);
    return update;
  }

  private void awaitStateWithSession(UUID expectedSession) throws InterruptedException {
    for (int attempt = 0; attempt < 40; attempt++) {
      Optional<TelegramChatState> state = stateStore.find(CHAT_ID);
      if (state.isPresent() && expectedSession.equals(state.get().sessionId())) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Telegram chat state was not updated with session " + expectedSession);
  }

  @TestConfiguration
  @EnableConfigurationProperties(TelegramBotProperties.class)
  static class TelegramTestConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }

    @Bean
    ProfileDevAuthProperties profileDevAuthProperties() {
      ProfileDevAuthProperties properties = new ProfileDevAuthProperties();
      properties.setEnabled(false);
      return properties;
    }
  }
}
