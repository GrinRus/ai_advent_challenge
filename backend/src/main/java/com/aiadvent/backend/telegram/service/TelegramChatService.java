package com.aiadvent.backend.telegram.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.service.ChatResearchToolBindingService;
import com.aiadvent.backend.chat.service.SyncChatService;
import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Component
public class TelegramChatService implements TelegramUpdateHandler {

  private static final Logger log = LoggerFactory.getLogger(TelegramChatService.class);

  private static final String COMMAND_START = "/start";
  private static final String COMMAND_NEW = "/new";
  private static final String COMMAND_MENU = "/menu";

  private static final String CALLBACK_MENU_MAIN = "menu:main";
  private static final String CALLBACK_MENU_PROVIDERS = "menu:providers";
  private static final String CALLBACK_MENU_MODELS = "menu:models:";
  private static final String CALLBACK_MENU_MODE = "menu:mode";
  private static final String CALLBACK_MENU_TOOLS = "menu:tools";
  private static final String CALLBACK_MENU_SAMPLING = "menu:sampling";

  private static final String CALLBACK_SET_PROVIDER = "set-provider:";
  private static final String CALLBACK_SET_MODEL = "set-model:";
  private static final String CALLBACK_SET_MODE = "set-mode:";
  private static final String CALLBACK_TOGGLE_TOOL = "toggle-tool:";
  private static final String CALLBACK_CLEAR_TOOLS = "tools:clear";
  private static final String CALLBACK_SET_TEMP = "sampling:temp:";
  private static final String CALLBACK_SET_TOP_P = "sampling:topp:";
  private static final String CALLBACK_SET_MAX = "sampling:max:";
  private static final String CALLBACK_RESET_SAMPLING = "sampling:reset";
  private static final String CALLBACK_NEW_DIALOG = "action:new";
  private static final String CALLBACK_STOP = "action:stop";
  private static final String CALLBACK_REPEAT = "action:repeat";
  private static final String CALLBACK_SHOW_DETAILS = "result:details";

  private static final List<Double> TEMPERATURE_OPTIONS = List.of(0.1, 0.3, 0.7, 1.0);
  private static final List<Double> TOP_P_OPTIONS = List.of(0.5, 0.8, 1.0);
  private static final List<Integer> MAX_TOKENS_OPTIONS = List.of(512, 1024, 2048);

  private static final String DEFAULT_MODE = "default";

  private final TelegramWebhookBotAdapter webhookBot;
  private final SyncChatService syncChatService;
  private final ChatProviderService chatProviderService;
  private final ChatResearchToolBindingService researchToolBindingService;
  private final TelegramChatStateStore stateStore;
  private final TelegramBotProperties properties;
  private final OpenAiAudioTranscriptionModel transcriptionModel;
  private final WebClient telegramFileClient;

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<Long, PendingRequest> activeRequests = new ConcurrentHashMap<>();

  public TelegramChatService(
      TelegramWebhookBotAdapter webhookBot,
      SyncChatService syncChatService,
      ChatProviderService chatProviderService,
      ChatResearchToolBindingService researchToolBindingService,
      TelegramChatStateStore stateStore,
      TelegramBotProperties properties,
      OpenAiAudioTranscriptionModel transcriptionModel,
      WebClient.Builder webClientBuilder) {
    this.webhookBot = webhookBot;
    this.syncChatService = syncChatService;
    this.chatProviderService = chatProviderService;
    this.researchToolBindingService = researchToolBindingService;
    this.stateStore = stateStore;
    this.properties = properties;
    this.transcriptionModel = transcriptionModel;
    this.telegramFileClient = webClientBuilder.build();
  }

  @Override
  public void handle(Update update) {
    if (update == null) {
      return;
    }

    try {
      if (update.hasMessage()) {
        handleMessage(update.getMessage());
      } else if (update.hasCallbackQuery()) {
        handleCallback(update.getCallbackQuery());
      } else {
        log.debug("Ignoring unsupported update type: {}", update);
      }
    } catch (Exception ex) {
      log.error("Failed to process Telegram update: {}", ex.getMessage(), ex);
    }
  }

  private void handleMessage(Message message) {
    if (message == null || message.getChatId() == null) {
      return;
    }

    long chatId = message.getChatId();

    if (message.getVoice() != null) {
      handleVoiceMessage(chatId, message.getVoice());
      return;
    }

    if (message.isCommand()) {
      processCommand(chatId, message.getText());
      return;
    }

    if (!message.hasText()) {
      sendText(chatId, "Поддерживаются только текстовые сообщения. Попробуйте снова.");
      return;
    }

    String text = message.getText();
    if (!StringUtils.hasText(text)) {
      sendText(chatId, "Сообщение пустое. Напишите, что бы вы хотели обсудить.");
      return;
    }

    processUserPrompt(chatId, text.trim());
  }

  private void processCommand(long chatId, String rawCommand) {
    String command = rawCommand != null ? rawCommand.trim().toLowerCase(Locale.ROOT) : "";
    switch (command) {
      case COMMAND_START -> {
        stateStore.reset(chatId);
        TelegramChatState state = ensureState(chatId);
        sendText(
            chatId,
            "Привет! Я Telegram-интерфейс AI Advent Chat. "
                + "Пиши вопросы, а я буду отвечать, используя модель "
                + formatModelDescriptor(state)
                + ".\nКоманды: /new — новый диалог, /menu — настройки.");
        sendMainMenu(chatId, state);
      }
      case COMMAND_NEW -> {
        stateStore.compute(chatId, () -> createDefaultState(chatId), TelegramChatState::resetSession);
        sendText(chatId, "Начинаем новый диалог. Чем могу помочь?");
      }
      case COMMAND_MENU -> sendMainMenu(chatId, ensureState(chatId));
      default -> sendText(chatId, "Неизвестная команда. Доступны /start, /new и /menu.");
    }
  }

  private void processUserPrompt(long chatId, String prompt) {
    PendingRequest existing = activeRequests.get(chatId);
    if (existing != null && !existing.cancelled.get()) {
      sendText(chatId, "Уже выполняется запрос. Нажмите «Остановить» или дождитесь завершения.");
      return;
    }

    TelegramChatState state = ensureState(chatId);
    ChatSyncRequest request = buildSyncRequest(state, prompt);

    PendingRequest pending = new PendingRequest(prompt);
    activeRequests.put(chatId, pending);
    sendProcessingNotice(chatId, state);

    CompletableFuture<Void> future =
        CompletableFuture.runAsync(
            () -> {
              try {
                SyncChatService.SyncChatResult result = syncChatService.sync(request);
                if (pending.cancelled.get()) {
                  log.debug("Skipping response for chat {} because request was cancelled", chatId);
                  return;
                }

                UUID nextSessionId = result.context().sessionId();
                TelegramChatState updatedState =
                    stateStore.compute(
                        chatId,
                        () -> createDefaultState(chatId).withLastResult(prompt, result.response()),
                        current -> {
                          TelegramChatState base =
                              current != null ? current : createDefaultState(chatId);
                          TelegramChatState withSession = base.withSessionId(nextSessionId);
                          return withSession.withLastResult(prompt, result.response());
                        });

                sendText(chatId, sanitizeResponse(result.response().content()));
                sendMetadataSummary(chatId, updatedState, result.response());
              } catch (ResponseStatusException apiError) {
                if (!pending.cancelled.get()) {
                  log.warn("LLM request failed: {}", apiError.getMessage());
                  sendText(
                      chatId,
                      "Не удалось получить ответ от модели ("
                          + apiError.getStatusCode()
                          + "). Попробуйте позже.");
                }
              } catch (Exception ex) {
                if (!pending.cancelled.get()) {
                  log.error("Unexpected error during sync call: {}", ex.getMessage(), ex);
                  sendText(chatId, "Произошла ошибка при обработке запроса. Попробуйте позже.");
                }
              } finally {
                activeRequests.remove(chatId, pending);
              }
            },
            executor);

    pending.future = future;
  }

  private ChatSyncRequest buildSyncRequest(TelegramChatState state, String prompt) {
    ChatStreamRequestOptions options = null;
    if (state.hasSamplingOverrides()) {
      options =
          new ChatStreamRequestOptions(
              state.temperatureOverride(), state.topPOverride(), state.maxTokensOverride());
    }

    List<String> toolCodes =
        state.interactionMode().equalsIgnoreCase("research")
            ? (CollectionUtils.isEmpty(state.requestedToolCodes())
                ? null
                : List.copyOf(state.requestedToolCodes()))
            : null;

    return new ChatSyncRequest(
        state.sessionId(),
        prompt,
        state.providerId(),
        state.modelId(),
        state.interactionMode(),
        toolCodes,
        options);
  }

  private void handleCallback(CallbackQuery callbackQuery) {
    if (callbackQuery == null || callbackQuery.getData() == null) {
      return;
    }

    long chatId = callbackQuery.getMessage() != null ? callbackQuery.getMessage().getChatId() : -1L;
    String data = callbackQuery.getData();

    try {
      if (CALLBACK_STOP.equals(data)) {
        handleStop(chatId, callbackQuery.getId());
      } else if (CALLBACK_REPEAT.equals(data)) {
        handleRepeat(chatId, callbackQuery.getId());
      } else if (CALLBACK_SHOW_DETAILS.equals(data)) {
        handleShowDetails(chatId, callbackQuery.getId());
      } else if (data.startsWith(CALLBACK_SET_PROVIDER)) {
        handleProviderSelection(chatId, data.substring(CALLBACK_SET_PROVIDER.length()), callbackQuery.getId());
      } else if (data.startsWith(CALLBACK_SET_MODEL)) {
        String payload = data.substring(CALLBACK_SET_MODEL.length());
        int idx = payload.indexOf(':');
        if (idx <= 0) {
          respondToCallback(callbackQuery.getId(), "Некорректная модель", true);
        } else {
          String providerId = payload.substring(0, idx);
          String modelId = payload.substring(idx + 1);
          handleModelSelection(chatId, providerId, modelId, callbackQuery.getId());
        }
      } else if (data.startsWith(CALLBACK_SET_MODE)) {
        handleModeSelection(chatId, data.substring(CALLBACK_SET_MODE.length()), callbackQuery.getId());
      } else if (CALLBACK_MENU_PROVIDERS.equals(data)) {
        sendProvidersMenu(chatId);
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (data.startsWith(CALLBACK_MENU_MODELS)) {
        sendModelMenu(chatId, data.substring(CALLBACK_MENU_MODELS.length()));
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_MENU_MODE.equals(data)) {
        sendModeMenu(chatId);
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_MENU_TOOLS.equals(data)) {
        sendToolsMenu(chatId, ensureState(chatId));
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_MENU_SAMPLING.equals(data)) {
        sendSamplingMenu(chatId, ensureState(chatId));
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (data.startsWith(CALLBACK_TOGGLE_TOOL)) {
        handleToolToggle(chatId, data.substring(CALLBACK_TOGGLE_TOOL.length()), callbackQuery.getId());
      } else if (CALLBACK_CLEAR_TOOLS.equals(data)) {
        stateStore.compute(chatId, () -> createDefaultState(chatId), TelegramChatState::clearToolCodes);
        respondToCallback(callbackQuery.getId(), "Инструменты сброшены", false);
        sendToolsMenu(chatId, ensureState(chatId));
      } else if (data.startsWith(CALLBACK_SET_TEMP)) {
        handleSamplingUpdate(chatId, parseDouble(data.substring(CALLBACK_SET_TEMP.length())), null, null, callbackQuery.getId());
      } else if (data.startsWith(CALLBACK_SET_TOP_P)) {
        handleSamplingUpdate(chatId, null, parseDouble(data.substring(CALLBACK_SET_TOP_P.length())), null, callbackQuery.getId());
      } else if (data.startsWith(CALLBACK_SET_MAX)) {
        handleSamplingUpdate(chatId, null, null, parseInteger(data.substring(CALLBACK_SET_MAX.length())), callbackQuery.getId());
      } else if (CALLBACK_RESET_SAMPLING.equals(data)) {
        stateStore.compute(chatId, () -> createDefaultState(chatId), TelegramChatState::resetSamplingOverrides);
        respondToCallback(callbackQuery.getId(), "Параметры sampling сброшены", false);
        sendSamplingMenu(chatId, ensureState(chatId));
      } else if (CALLBACK_MENU_MAIN.equals(data)) {
        sendMainMenu(chatId, ensureState(chatId));
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_NEW_DIALOG.equals(data)) {
        stateStore.compute(chatId, () -> createDefaultState(chatId), TelegramChatState::resetSession);
        respondToCallback(callbackQuery.getId(), "Диалог сброшен", false);
        sendMainMenu(chatId, ensureState(chatId));
      } else {
        respondToCallback(callbackQuery.getId(), "Действие не поддерживается", true);
      }
    } catch (Exception ex) {
      log.error("Failed to process callback {}: {}", data, ex.getMessage(), ex);
      try {
        respondToCallback(callbackQuery.getId(), "Ошибка при обработке действия", true);
      } catch (TelegramApiException ignore) {
        log.debug("Failed to notify user about callback error");
      }
    }
  }

  private void handleStop(long chatId, String callbackId) throws TelegramApiException {
    PendingRequest pending = activeRequests.remove(chatId);
    if (pending == null) {
      respondToCallback(callbackId, "Нет активного запроса", true);
      return;
    }
    pending.cancelled.set(true);
    if (pending.future != null) {
      pending.future.cancel(true);
    }
    respondToCallback(callbackId, "Запрос остановлен", false);
  }

  private void handleRepeat(long chatId, String callbackId) throws TelegramApiException {
    if (activeRequests.containsKey(chatId)) {
      respondToCallback(callbackId, "Сначала завершите текущий запрос", true);
      return;
    }
    TelegramChatState state = ensureState(chatId);
    if (!StringUtils.hasText(state.lastPrompt())) {
      respondToCallback(callbackId, "Нет предыдущего запроса для повтора", true);
      return;
    }
    respondToCallback(callbackId, "Повторяем предыдущий запрос", false);
    processUserPrompt(chatId, state.lastPrompt());
  }

  private void handleShowDetails(long chatId, String callbackId) throws TelegramApiException {
    TelegramChatState state = ensureState(chatId);
    ChatSyncResponse response = state.lastResponse();
    if (response == null) {
      respondToCallback(callbackId, "Подробностей пока нет", true);
      return;
    }
    respondToCallback(callbackId, null, false);
    sendDetails(chatId, state, response);
  }

  private TelegramChatState ensureState(long chatId) {
    return stateStore.getOrCreate(chatId, () -> createDefaultState(chatId));
  }

  private TelegramChatState createDefaultState(long chatId) {
    ChatProviderSelection selection = chatProviderService.resolveSelection(null, null);
    return TelegramChatState.create(chatId, selection.providerId(), selection.modelId());
  }

  private void sendText(long chatId, String text) {
    sendMessage(chatId, text, null);
  }

  private void sendMessage(long chatId, String text, InlineKeyboardMarkup markup) {
    SendMessage.SendMessageBuilder builder =
        SendMessage.builder().chatId(Long.toString(chatId)).text(text).disableWebPagePreview(true);
    if (markup != null) {
      builder.replyMarkup(markup);
    }
    try {
      webhookBot.execute(builder.build());
    } catch (TelegramApiException ex) {
      log.warn("Failed to send Telegram message: {}", ex.getMessage());
    }
  }

  private void sendProcessingNotice(long chatId, TelegramChatState state) {
    InlineKeyboardMarkup markup =
        new InlineKeyboardMarkup(
            List.of(
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Остановить")
                        .callbackData(CALLBACK_STOP)
                        .build())));

    String text =
        "Отправил запрос → "
            + state.providerId()
            + "/"
            + state.modelId()
            + " (режим: "
            + state.interactionMode()
            + ")";
    sendMessage(chatId, text, markup);
  }

  private void sendMetadataSummary(long chatId, TelegramChatState state, ChatSyncResponse response) {
    StringBuilder builder =
        new StringBuilder("Ответ готов:\n")
            .append("• Провайдер: ")
            .append(state.providerId())
            .append('\n')
            .append("• Модель: ")
            .append(state.modelId())
            .append('\n')
            .append("• Режим: ")
            .append(state.interactionMode());

    if (!state.requestedToolCodes().isEmpty()) {
      builder.append('\n').append("• Инструменты: ").append(String.join(", ", state.requestedToolCodes()));
    }

    if (response.latencyMs() != null) {
      builder.append('\n').append("• Задержка: ").append(response.latencyMs()).append(" мс");
    }

    InlineKeyboardMarkup markup =
        new InlineKeyboardMarkup(
            List.of(
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Подробнее")
                        .callbackData(CALLBACK_SHOW_DETAILS)
                        .build()),
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Повторить")
                        .callbackData(CALLBACK_REPEAT)
                        .build(),
                    InlineKeyboardButton.builder()
                        .text("Меню")
                        .callbackData(CALLBACK_MENU_MAIN)
                        .build())));

    sendMessage(chatId, builder.toString(), markup);
  }

  private void sendMainMenu(long chatId, TelegramChatState state) {
    InlineKeyboardMarkup markup =
        new InlineKeyboardMarkup(
            List.of(
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Провайдер")
                        .callbackData(CALLBACK_MENU_PROVIDERS)
                        .build(),
                    InlineKeyboardButton.builder()
                        .text("Модели")
                        .callbackData(CALLBACK_MENU_MODELS + state.providerId())
                        .build()),
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Режим")
                        .callbackData(CALLBACK_MENU_MODE)
                        .build(),
                    InlineKeyboardButton.builder()
                        .text("Инструменты")
                        .callbackData(CALLBACK_MENU_TOOLS)
                        .build()),
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Sampling")
                        .callbackData(CALLBACK_MENU_SAMPLING)
                        .build(),
                    InlineKeyboardButton.builder()
                        .text("Новый диалог")
                        .callbackData(CALLBACK_NEW_DIALOG)
                        .build())));

    StringBuilder summary =
        new StringBuilder("Текущие настройки:\n")
            .append("• Провайдер: ")
            .append(state.providerId())
            .append('\n')
            .append("• Модель: ")
            .append(state.modelId())
            .append('\n')
            .append("• Режим: ")
            .append(state.interactionMode());

    if (!state.requestedToolCodes().isEmpty()) {
      summary.append('\n').append("• Инструменты: ").append(String.join(", ", state.requestedToolCodes()));
    } else {
      summary.append('\n').append("• Инструменты: не выбраны");
    }

    if (state.hasSamplingOverrides()) {
      summary
          .append('\n')
          .append("• Sampling: temp=")
          .append(formatDouble(state.temperatureOverride()))
          .append(", topP=")
          .append(formatDouble(state.topPOverride()))
          .append(", maxTokens=")
          .append(state.maxTokensOverride() != null ? state.maxTokensOverride() : "—");
    } else {
      summary.append('\n').append("• Sampling: по умолчанию");
    }

    if (state.sessionId() != null) {
      summary.append("\n• sessionId: ").append(state.sessionId());
    }

    sendMessage(chatId, summary.toString(), markup);
  }

  private void sendProvidersMenu(long chatId) {
    Map<String, ChatProvidersProperties.Provider> providers = chatProviderService.providers();
    if (providers.isEmpty()) {
      sendText(chatId, "Доступные провайдеры не найдены.");
      return;
    }

    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    providers.forEach(
        (id, provider) ->
            rows.add(
                List.of(
                    InlineKeyboardButton.builder()
                        .text(provider.getDisplayName() != null ? provider.getDisplayName() : id)
                        .callbackData(CALLBACK_SET_PROVIDER + id)
                        .build())));
    rows.add(backButtonRow());

    sendMessage(chatId, "Выберите провайдера:", new InlineKeyboardMarkup(rows));
  }

  private void sendModelMenu(long chatId, String providerId) {
    ChatProvidersProperties.Provider provider;
    try {
      provider = chatProviderService.provider(providerId);
    } catch (IllegalArgumentException ex) {
      sendText(chatId, "Провайдер " + providerId + " не найден.");
      return;
    }

    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    provider
        .getModels()
        .forEach(
            (modelId, model) -> {
              if (Boolean.TRUE.equals(model.isSyncEnabled())) {
                String label = model.getDisplayName() != null ? model.getDisplayName() : modelId;
                rows.add(
                    List.of(
                        InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData(CALLBACK_SET_MODEL + providerId + ":" + modelId)
                            .build()));
              }
            });
    rows.add(backButtonRow());

    sendMessage(chatId, "Выберите модель для " + providerId + ":", new InlineKeyboardMarkup(rows));
  }

  private void sendModeMenu(long chatId) {
    InlineKeyboardMarkup markup =
        new InlineKeyboardMarkup(
            List.of(
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Стандартный (sync)")
                        .callbackData(CALLBACK_SET_MODE + DEFAULT_MODE)
                        .build()),
                List.of(
                    InlineKeyboardButton.builder()
                        .text("Research (MCP)")
                        .callbackData(CALLBACK_SET_MODE + "research")
                        .build()),
                backButtonRow()));

    sendMessage(chatId, "Выберите режим работы:", markup);
  }

  private void sendToolsMenu(long chatId, TelegramChatState state) {
    List<String> toolCodes = researchToolBindingService.availableToolCodes();
    if (toolCodes.isEmpty()) {
      sendText(chatId, "Инструменты не сконфигурированы. Проверьте настройки backend.");
      return;
    }

    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    for (String code : toolCodes) {
      boolean enabled = state.hasToolCode(code);
      rows.add(
          List.of(
              InlineKeyboardButton.builder()
                  .text((enabled ? "✅ " : "▫️ ") + code)
                  .callbackData(CALLBACK_TOGGLE_TOOL + code)
                  .build()));
    }
    rows.add(
        List.of(
            InlineKeyboardButton.builder()
                .text("Очистить")
                .callbackData(CALLBACK_CLEAR_TOOLS)
                .build()));
    rows.add(backButtonRow());

    String hint =
        state.interactionMode().equalsIgnoreCase("research")
            ? "Выберите инструменты для режима research."
            : "Инструменты активируются только в режиме research.\nСмените режим через главное меню.";

    sendMessage(chatId, hint, new InlineKeyboardMarkup(rows));
  }

  private void sendSamplingMenu(long chatId, TelegramChatState state) {
    ChatProvidersProperties.Provider providerConfig =
        chatProviderService.provider(state.providerId());
    ChatProvidersProperties.Model modelConfig =
        chatProviderService.model(state.providerId(), state.modelId());

    List<List<InlineKeyboardButton>> rows = new ArrayList<>();

    List<InlineKeyboardButton> tempRow = new ArrayList<>();
    for (Double value : TEMPERATURE_OPTIONS) {
      tempRow.add(
          InlineKeyboardButton.builder()
              .text(labelForOption("T", value, state.temperatureOverride()))
              .callbackData(CALLBACK_SET_TEMP + value)
              .build());
    }
    rows.add(tempRow);

    List<InlineKeyboardButton> topRow = new ArrayList<>();
    for (Double value : TOP_P_OPTIONS) {
      topRow.add(
          InlineKeyboardButton.builder()
              .text(labelForOption("P", value, state.topPOverride()))
              .callbackData(CALLBACK_SET_TOP_P + value)
              .build());
    }
    rows.add(topRow);

    List<InlineKeyboardButton> maxRow = new ArrayList<>();
    for (Integer value : MAX_TOKENS_OPTIONS) {
      maxRow.add(
          InlineKeyboardButton.builder()
              .text(labelForOption("M", value, state.maxTokensOverride()))
              .callbackData(CALLBACK_SET_MAX + value)
              .build());
    }
    rows.add(maxRow);

    rows.add(
        List.of(
            InlineKeyboardButton.builder()
                .text("Сбросить")
                .callbackData(CALLBACK_RESET_SAMPLING)
                .build()));
    rows.add(backButtonRow());

    StringBuilder text =
        new StringBuilder("Sampling overrides:\n")
            .append("• Temperature: ")
            .append(state.temperatureOverride() != null ? state.temperatureOverride() : "по умолчанию")
            .append(" (default: ")
            .append(providerConfig.getTemperature() != null ? providerConfig.getTemperature() : "—")
            .append(")\n")
            .append("• TopP: ")
            .append(state.topPOverride() != null ? state.topPOverride() : "по умолчанию")
            .append(" (default: ")
            .append(providerConfig.getTopP() != null ? providerConfig.getTopP() : "—")
            .append(")\n")
            .append("• Max tokens: ")
            .append(state.maxTokensOverride() != null ? state.maxTokensOverride() : "по умолчанию")
            .append(" (default: ")
            .append(
                providerConfig.getMaxTokens() != null
                    ? providerConfig.getMaxTokens()
                    : (modelConfig.getMaxOutputTokens() != null ? modelConfig.getMaxOutputTokens() : "—"))
            .append(")");

    sendMessage(chatId, text.toString(), new InlineKeyboardMarkup(rows));
  }

  private void handleProviderSelection(long chatId, String providerId, String callbackId)
      throws TelegramApiException {
    try {
      ChatProvidersProperties.Provider provider = chatProviderService.provider(providerId);
      String defaultModel = provider.getDefaultModel();
      stateStore.compute(
          chatId,
          () -> TelegramChatState.create(chatId, providerId, defaultModel),
          current ->
              (current != null ? current : TelegramChatState.create(chatId, providerId, defaultModel))
                  .withProviderAndModel(providerId, defaultModel)
                  .resetSession());
      respondToCallback(callbackId, "Провайдер выбран", false);
      sendModelMenu(chatId, providerId);
    } catch (IllegalArgumentException ex) {
      log.warn("Unknown provider {}", providerId);
      respondToCallback(callbackId, "Неизвестный провайдер", true);
    }
  }

  private void handleModelSelection(
      long chatId, String providerId, String modelId, String callbackId)
      throws TelegramApiException {
    try {
      ChatProvidersProperties.Model model = chatProviderService.model(providerId, modelId);
      if (!Boolean.TRUE.equals(model.isSyncEnabled())) {
        respondToCallback(callbackId, "Модель не поддерживает sync", true);
        return;
      }

      stateStore.compute(
          chatId,
          () -> TelegramChatState.create(chatId, providerId, modelId),
          current ->
              (current != null ? current : TelegramChatState.create(chatId, providerId, modelId))
                  .withProviderAndModel(providerId, modelId)
                  .resetSession());

      respondToCallback(callbackId, "Модель обновлена", false);
      sendMainMenu(chatId, ensureState(chatId));
    } catch (IllegalArgumentException ex) {
      log.warn("Unknown model {} for provider {}", modelId, providerId);
      respondToCallback(callbackId, "Неизвестная модель", true);
    }
  }

  private void handleModeSelection(long chatId, String rawMode, String callbackId)
      throws TelegramApiException {
    ChatInteractionMode mode = ChatInteractionMode.from(rawMode);
    stateStore.compute(
        chatId,
        () -> createDefaultState(chatId),
        current ->
            (current != null ? current : createDefaultState(chatId))
                .withInteractionMode(mode.name().toLowerCase(Locale.ROOT))
                .resetSession());

    respondToCallback(callbackId, "Режим обновлён", false);
    sendMainMenu(chatId, ensureState(chatId));
  }

  private void handleToolToggle(long chatId, String toolCode, String callbackId)
      throws TelegramApiException {
    stateStore.compute(
        chatId,
        () -> createDefaultState(chatId),
        current ->
            (current != null ? current : createDefaultState(chatId))
                .toggleToolCode(toolCode)
                .resetSession());

    respondToCallback(callbackId, null, false);
    sendToolsMenu(chatId, ensureState(chatId));
  }

  private void handleSamplingUpdate(
      long chatId,
      Double newTemperature,
      Double newTopP,
      Integer newMaxTokens,
      String callbackId)
      throws TelegramApiException {
    stateStore.compute(
        chatId,
        () -> createDefaultState(chatId),
        current -> {
          TelegramChatState base = current != null ? current : createDefaultState(chatId);
          Double temperature = newTemperature != null ? newTemperature : base.temperatureOverride();
          Double topP = newTopP != null ? newTopP : base.topPOverride();
          Integer maxTokens = newMaxTokens != null ? newMaxTokens : base.maxTokensOverride();
          return base.withSamplingOverrides(temperature, topP, maxTokens).resetSession();
        });

    respondToCallback(callbackId, "Параметр обновлён", false);
    sendSamplingMenu(chatId, ensureState(chatId));
  }

  private void handleVoiceMessage(long chatId, Voice voice) {
    if (!properties.getStt().isEnabled()) {
      sendText(chatId, "Голосовые сообщения отключены настройками.");
      return;
    }
    if (voice == null) {
      return;
    }
    try {
      DownloadedAudio audio = fetchVoiceFile(voice);
      if (audio == null || audio.data().length == 0) {
        sendText(chatId, "Не удалось загрузить голосовое сообщение.");
        return;
      }
      String transcript = transcribeAudio(audio);
      if (!StringUtils.hasText(transcript)) {
        sendText(chatId, "Не удалось распознать голосовое сообщение.");
        return;
      }
      String normalized = transcript.trim();
      sendText(chatId, "🎤 " + normalized);
      processUserPrompt(chatId, normalized);
    } catch (Exception ex) {
      log.error("Failed to process voice message for chat {}: {}", chatId, ex.getMessage());
      sendText(chatId, "Не удалось обработать голосовое сообщение. Попробуйте снова.");
    }
  }

  private DownloadedAudio fetchVoiceFile(Voice voice) throws TelegramApiException {
    GetFile getFile = new GetFile(voice.getFileId());
    File telegramFile = webhookBot.execute(getFile);
    String filePath = telegramFile != null ? telegramFile.getFilePath() : null;
    if (!StringUtils.hasText(filePath)) {
      return null;
    }
    String token = properties.getBot().getToken();
    if (!StringUtils.hasText(token)) {
      throw new IllegalStateException("Telegram bot token is not configured");
    }
    byte[] bytes;
    try {
      bytes =
          telegramFileClient
              .get()
              .uri("https://api.telegram.org/file/bot{token}/{path}", token, filePath)
              .retrieve()
              .bodyToMono(byte[].class)
              .block();
    } catch (WebClientResponseException ex) {
      log.warn("Failed to download Telegram file {}: {}", filePath, ex.getMessage());
      return null;
    } catch (RuntimeException ex) {
      log.warn("Failed to download Telegram file {}", filePath, ex);
      return null;
    }
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    String baseName = voice.getFileUniqueId() != null ? voice.getFileUniqueId() : voice.getFileId();
    String extension = "oga";
    int dot = filePath.lastIndexOf('.');
    if (dot > 0 && dot < filePath.length() - 1) {
      extension = filePath.substring(dot + 1);
    }
    String filename = baseName + "." + extension;
    return new DownloadedAudio(bytes, filename);
  }

  private String transcribeAudio(DownloadedAudio audio) {
    if (audio == null || audio.data().length == 0) {
      return null;
    }
    String primaryModel = properties.getStt().getModel();
    String fallbackModel = properties.getStt().getFallbackModel();

    String transcript = transcribeWithModel(audio, primaryModel);
    if (!StringUtils.hasText(transcript) && StringUtils.hasText(fallbackModel)) {
      log.debug("Primary STT model {} failed, trying fallback {}", primaryModel, fallbackModel);
      transcript = transcribeWithModel(audio, fallbackModel);
    }
    return transcript;
  }

  private String transcribeWithModel(DownloadedAudio audio, String modelId) {
    if (!StringUtils.hasText(modelId)) {
      return null;
    }
    try {
      Resource resource =
          new ByteArrayResource(audio.data()) {
            @Override
            public String getFilename() {
              return audio.filename();
            }
          };
      OpenAiAudioTranscriptionOptions options = new OpenAiAudioTranscriptionOptions();
      options.setModel(modelId);
      if (StringUtils.hasText(properties.getStt().getLanguage())) {
        options.setLanguage(properties.getStt().getLanguage());
      }
      AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(resource, options);
      AudioTranscriptionResponse response = transcriptionModel.call(prompt);
      if (response != null && response.getResult() != null) {
        return response.getResult().getOutput();
      }
    } catch (Exception ex) {
      log.warn("Transcription with model {} failed: {}", modelId, ex.getMessage());
    }
    return null;
  }

  private void sendDetails(long chatId, TelegramChatState state, ChatSyncResponse response) {
    StringBuilder builder = new StringBuilder("Детали последнего ответа:\n");
    builder
        .append("• sessionId: ")
        .append(state.sessionId() != null ? state.sessionId() : "—")
        .append('\n')
        .append("• requestId: ")
        .append(response.requestId() != null ? response.requestId() : "—")
        .append('\n');

    StructuredSyncUsageStats usage = response.usage();
    if (usage != null) {
      builder
          .append("• Tokens: prompt=")
          .append(usage.promptTokens() != null ? usage.promptTokens() : "?")
          .append(", completion=")
          .append(usage.completionTokens() != null ? usage.completionTokens() : "?")
          .append(", total=")
          .append(usage.totalTokens() != null ? usage.totalTokens() : "?")
          .append('\n');
    }

    UsageCostDetails cost = response.cost();
    if (cost != null && (hasValue(cost.input()) || hasValue(cost.output()) || hasValue(cost.total()))) {
      builder
          .append("• Стоимость: input=")
          .append(formatMoney(cost.input()))
          .append(", output=")
          .append(formatMoney(cost.output()))
          .append(", total=")
          .append(formatMoney(cost.total()))
          .append(' ')
          .append(cost.currency() != null ? cost.currency() : "");
    }

    sendText(chatId, builder.toString());
  }

  private String formatMoney(BigDecimal value) {
    if (value == null) {
      return "—";
    }
    return value.stripTrailingZeros().toPlainString();
  }

  private boolean hasValue(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  private List<InlineKeyboardButton> backButtonRow() {
    return List.of(
        InlineKeyboardButton.builder().text("← Назад").callbackData(CALLBACK_MENU_MAIN).build());
  }

  private String labelForOption(String prefix, Double value, Double current) {
    boolean active = current != null && Math.abs(current - value) < 1e-9;
    return (active ? "✅ " : "▫️ ") + prefix + "=" + formatDouble(value);
  }

  private String labelForOption(String prefix, Integer value, Integer current) {
    boolean active = current != null && current.equals(value);
    return (active ? "✅ " : "▫️ ") + prefix + "=" + value;
  }

  private String formatDouble(Double value) {
    if (value == null) {
      return "—";
    }
    String formatted = String.format(Locale.US, "%.2f", value);
    formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    return formatted;
  }

  private Double parseDouble(String raw) {
    try {
      return Double.valueOf(raw);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Integer parseInteger(String raw) {
    try {
      return Integer.valueOf(raw);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private void respondToCallback(String callbackId, String message, boolean alert)
      throws TelegramApiException {
    AnswerCallbackQuery.AnswerCallbackQueryBuilder builder =
        AnswerCallbackQuery.builder().callbackQueryId(callbackId).showAlert(alert);
    if (StringUtils.hasText(message)) {
      builder.text(message);
    }
    webhookBot.execute(builder.build());
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }

  private String sanitizeResponse(String content) {
    if (!StringUtils.hasText(content)) {
      return "Модель вернула пустой ответ.";
    }
    return content.trim();
  }

  private String formatModelDescriptor(TelegramChatState state) {
    return state.providerId() + "/" + state.modelId();
  }

  private record DownloadedAudio(byte[] data, String filename) {}

  private static final class PendingRequest {
    final String prompt;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    volatile CompletableFuture<?> future;

    PendingRequest(String prompt) {
      this.prompt = prompt;
    }
  }
}
