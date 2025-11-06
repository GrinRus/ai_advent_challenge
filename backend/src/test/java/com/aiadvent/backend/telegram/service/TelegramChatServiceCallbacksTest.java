package com.aiadvent.backend.telegram.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.service.ChatResearchToolBindingService;
import com.aiadvent.backend.chat.service.ConversationContext;
import com.aiadvent.backend.chat.service.SyncChatService;
import com.aiadvent.backend.chat.service.SyncChatService.SyncChatResult;
import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Voice;

@ExtendWith(MockitoExtension.class)
class TelegramChatServiceCallbacksTest {

  private static final long CHAT_ID = 424242L;

  @Mock private TelegramWebhookBotAdapter webhookBot;
  @Mock private SyncChatService syncChatService;
  @Mock private ChatProviderService chatProviderService;
  @Mock private ChatResearchToolBindingService researchToolBindingService;
  @Mock private ObjectProvider<OpenAiAudioTranscriptionModel> transcriptionModelProvider;
  @Mock private OpenAiAudioTranscriptionModel transcriptionModel;
  @Mock private WebClient.Builder webClientBuilder;
  @Mock private WebClient telegramWebClient;

  private TelegramChatStateStore stateStore;
  private TelegramBotProperties properties;
  private TelegramChatService service;

  @BeforeEach
  void setUp() throws TelegramApiException {
    stateStore = new TelegramChatStateStore();

    properties = new TelegramBotProperties();
    properties.setEnabled(true);
    properties.getBot().setToken("token");
    properties.getBot().setUsername("bot");
    properties.getWebhook().setExternalUrl("https://example.com");
    properties.getWebhook().setPath("/telegram/update");
    properties.getWebhook().setSecretToken("secret");
    properties.getStt().setEnabled(true);
    properties.getStt().setModel("primary");
    properties.getStt().setFallbackModel("fallback");

    when(transcriptionModelProvider.getIfAvailable()).thenReturn(transcriptionModel);
    when(webClientBuilder.build()).thenReturn(telegramWebClient);
    lenient()
        .when(chatProviderService.resolveSelection(null, null))
        .thenReturn(new ChatProviderSelection("openai", "gpt-4o-mini"));

    service =
        new TelegramChatService(
            webhookBot,
            syncChatService,
            chatProviderService,
            researchToolBindingService,
            stateStore,
            properties,
            transcriptionModelProvider,
            webClientBuilder,
            new ObjectMapper());

    when(webhookBot.execute(any(SendMessage.class))).thenReturn(new Message());
    lenient().when(webhookBot.execute(any(AnswerCallbackQuery.class))).thenReturn(Boolean.TRUE);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  @Test
  void setProviderCallbackUpdatesStateAndShowsModelMenu() throws Exception {
    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    provider.setDefaultModel("gpt-4o-mini");
    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    model.setDisplayName("GPT-4o Mini");
    model.setSyncEnabled(true);
    provider.getModels().put("gpt-4o-mini", model);

    when(chatProviderService.provider("openai")).thenReturn(provider);

    service.handle(callbackUpdate("set-provider:openai"));

    TelegramChatState state = stateStore.find(CHAT_ID).orElseThrow();
    assertThat(state.providerId()).isEqualTo("openai");
    assertThat(state.modelId()).isEqualTo("gpt-4o-mini");
    assertThat(state.sessionId()).isNull();

    ArgumentCaptor<AnswerCallbackQuery> answerCaptor =
        ArgumentCaptor.forClass(AnswerCallbackQuery.class);
    verify(webhookBot, atLeastOnce()).execute(answerCaptor.capture());
    assertThat(answerCaptor.getValue().getText()).isEqualTo("Провайдер выбран");

    ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
    verify(webhookBot, atLeastOnce()).execute(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getText()).startsWith("Выберите модель");
  }

  @Test
  void setModelCallbackUpdatesStateAndResetsSession() throws Exception {
    ChatProvidersProperties.Model newModel = new ChatProvidersProperties.Model();
    newModel.setDisplayName("GLM-4.6");
    newModel.setSyncEnabled(true);

    when(chatProviderService.model("zhipu", "glm-4.6")).thenReturn(newModel);

    stateStore.compute(
        CHAT_ID,
        () -> TelegramChatState.create(CHAT_ID, "openai", "gpt-4o-mini"),
        current -> current.withProviderAndModel("zhipu", "glm-4.5").withSessionId(UUID.randomUUID()));

    service.handle(callbackUpdate("set-model:zhipu:glm-4.6"));

    TelegramChatState state = stateStore.find(CHAT_ID).orElseThrow();
    assertThat(state.providerId()).isEqualTo("zhipu");
    assertThat(state.modelId()).isEqualTo("glm-4.6");
    assertThat(state.sessionId()).isNull();

    verify(webhookBot).execute(any(AnswerCallbackQuery.class));
    verify(webhookBot, atLeastOnce()).execute(any(SendMessage.class));
  }

  @Test
  void setModeCallbackSwitchesInteractionMode() throws Exception {
    UUID sessionId = UUID.randomUUID();
    stateStore.compute(
        CHAT_ID,
        () -> TelegramChatState.create(CHAT_ID, "openai", "gpt-4o-mini"),
        current -> current.withSessionId(sessionId));

    service.handle(callbackUpdate("set-mode:research"));

    TelegramChatState state = stateStore.find(CHAT_ID).orElseThrow();
    assertThat(state.interactionMode()).isEqualTo("research");
    assertThat(state.sessionId()).isNull();
  }

  @Test
  void toggleToolCallbackUpdatesToolList() throws Exception {
    when(researchToolBindingService.availableToolNamespaces())
        .thenReturn(Map.of("agent_ops", List.of("agent_ops.list_agents"), "insight", List.of("insight.fetch_metrics")));

    stateStore.compute(
        CHAT_ID,
        () -> TelegramChatState.create(CHAT_ID, "openai", "gpt-4o-mini"),
        current -> current.withInteractionMode("research"));

    service.handle(callbackUpdate("toggle-tool:agent_ops"));

    TelegramChatState state = stateStore.find(CHAT_ID).orElseThrow();
    assertThat(state.requestedToolNamespaces()).containsExactly("agent_ops");

    verify(webhookBot).execute(any(AnswerCallbackQuery.class));
    verify(webhookBot, atLeastOnce()).execute(any(SendMessage.class));
  }

  @Test
  void textMessageUsesSelectedToolsRegardlessOfMode() throws Exception {
    when(researchToolBindingService.availableToolNamespaces())
        .thenReturn(Map.of("agent_ops", List.of("agent_ops.list_agents")));
    when(researchToolBindingService.resolveToolCodesForNamespaces(List.of("agent_ops")))
        .thenReturn(List.of("agent_ops.list_agents"));

    AtomicReference<ChatSyncRequest> capturedRequest = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    when(syncChatService.sync(any()))
        .thenAnswer(
            invocation -> {
              ChatSyncRequest request = invocation.getArgument(0);
              capturedRequest.set(request);
              latch.countDown();
              return new SyncChatResult(
                  new ConversationContext(UUID.randomUUID(), true, UUID.randomUUID()),
                  new ChatSyncResponse(
                      UUID.randomUUID(),
                      "Ответ",
                      new StructuredSyncProvider("OPENAI", "gpt-4o-mini"),
                      List.of("agent_ops.list_agents"),
                      null,
                      new StructuredSyncUsageStats(5, 7, 12),
                      new UsageCostDetails(
                          new BigDecimal("0.0001"),
                          new BigDecimal("0.0002"),
                          new BigDecimal("0.0003"),
                          "USD"),
                      30L,
                      Instant.now()));
            });

    service.handle(callbackUpdate("toggle-tool:agent_ops"));

    service.handle(textUpdate("Запрос через голос"));

    assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    ChatSyncRequest request = capturedRequest.get();
    assertThat(request).isNotNull();
    assertThat(request.mode()).isEqualTo("research");
    assertThat(request.requestedToolCodes()).containsExactly("agent_ops.list_agents");
  }

  @Test
  void stopCallbackCancelsActiveRequest() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);

    when(syncChatService.sync(any()))
        .thenAnswer(
            invocation -> {
              startLatch.countDown();
              try {
                completionLatch.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
              }
              return new SyncChatResult(
                  new ConversationContext(UUID.randomUUID(), false, UUID.randomUUID()),
                  new ChatSyncResponse(
                      UUID.randomUUID(),
                      "ignored",
                      new StructuredSyncProvider("OPENAI", "gpt-4o-mini"),
                      null,
                      null,
                      new StructuredSyncUsageStats(1, 1, 2),
                      new UsageCostDetails(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
                      10L,
                      Instant.now()));
            });

    service.handle(textUpdate("Тест остановки"));
    assertThat(startLatch.await(1, TimeUnit.SECONDS)).isTrue();

    service.handle(callbackUpdate("action:stop"));

    verify(webhookBot, timeout(1000)).execute(any(AnswerCallbackQuery.class));

    completionLatch.countDown();
  }

  @Test
  void repeatCallbackReplaysLastPrompt() throws Exception {
    when(syncChatService.sync(any()))
        .thenReturn(
            new SyncChatResult(
                new ConversationContext(UUID.randomUUID(), true, UUID.randomUUID()),
                new ChatSyncResponse(
                    UUID.randomUUID(),
                    "Ответ",
                    new StructuredSyncProvider("OPENAI", "gpt-4o-mini"),
                    null,
                    null,
                    new StructuredSyncUsageStats(5, 7, 12),
                    new UsageCostDetails(
                        new BigDecimal("0.0001"),
                        new BigDecimal("0.0002"),
                        new BigDecimal("0.0003"),
                        "USD"),
                    30L,
                    Instant.now())));

    stateStore.compute(
        CHAT_ID,
        () -> TelegramChatState.create(CHAT_ID, "openai", "gpt-4o-mini"),
        current -> current.withLastResult("Повтори", null));

    service.handle(callbackUpdate("action:repeat"));

    verify(syncChatService, timeout(1000)).sync(any(ChatSyncRequest.class));
  }

  @Test
  void userPromptInjectsTelegramOverrides() throws Exception {
    AutoCloseable scope = () -> {};
    ArgumentCaptor<Map<String, JsonNode>> overridesCaptor = ArgumentCaptor.forClass(Map.class);
    when(researchToolBindingService.withRequestOverrides(overridesCaptor.capture()))
        .thenReturn(scope);

    CountDownLatch latch = new CountDownLatch(1);
    ChatSyncResponse response =
        new ChatSyncResponse(
            UUID.randomUUID(),
            "ok",
            new StructuredSyncProvider("OPENAI", "gpt-4o-mini"),
            List.of(),
            null,
            new StructuredSyncUsageStats(1, 1, 2),
            new UsageCostDetails(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            25L,
            Instant.now());

    when(syncChatService.sync(any()))
        .thenAnswer(
            invocation -> {
              latch.countDown();
              return new SyncChatResult(
                  new ConversationContext(UUID.randomUUID(), true, UUID.randomUUID()), response);
            });

    service.handle(textUpdate("Сохрани эту заметку"));

    assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    verify(researchToolBindingService).withRequestOverrides(anyMap());
    Map<String, JsonNode> overrides = overridesCaptor.getValue();
    assertThat(overrides)
        .containsKeys("notes.save_note", "notes.search_similar");
    assertThat(overrides.get("notes.save_note").path("userNamespace").asText()).isEqualTo("telegram");
    assertThat(overrides.get("notes.save_note").path("userReference").asText()).isEqualTo("100");
    assertThat(overrides.get("notes.save_note").path("sourceChannel").asText()).isEqualTo("telegram");
  }

  @Test
  void voiceMessageWithSttDisabledNotifiesUser() throws Exception {
    properties.getStt().setEnabled(false);

    Voice voice = new Voice();
    voice.setFileId("file-id");
    voice.setDuration(1);

    Update update = new Update();
    Message message = new Message();
    Chat chat = new Chat();
    chat.setId(CHAT_ID);
    message.setChat(chat);
    message.setVoice(voice);
    update.setMessage(message);

    service.handle(update);

    ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
    verify(webhookBot).execute(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getText()).contains("Голосовые сообщения отключены");
  }

  private Update callbackUpdate(String data) {
    Update update = new Update();
    CallbackQuery callback = new CallbackQuery();
    callback.setId("cb-" + data);
    callback.setData(data);

    Message message = new Message();
    Chat chat = new Chat();
    chat.setId(CHAT_ID);
    message.setChat(chat);
    message.setMessageId(10);
    callback.setMessage(message);

    User user = new User();
    user.setId(99L);
    user.setIsBot(false);
    callback.setFrom(user);

    update.setCallbackQuery(callback);
    return update;
  }

  private Update textUpdate(String text) {
    Update update = new Update();
    Message message = new Message();
    Chat chat = new Chat();
    chat.setId(CHAT_ID);
    message.setChat(chat);
    message.setMessageId(1);
    message.setText(text);
    User user = new User();
    user.setId(100L);
    user.setIsBot(false);
    message.setFrom(user);
    update.setMessage(message);
    return update;
  }
}
