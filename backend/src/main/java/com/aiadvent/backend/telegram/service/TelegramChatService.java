package com.aiadvent.backend.telegram.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.service.SyncChatService;
import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
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
  private static final String CALLBACK_MENU_MODE = "menu:mode";
  private static final String CALLBACK_MENU_MODELS = "menu:models:";
  private static final String CALLBACK_SET_PROVIDER = "set-provider:";
  private static final String CALLBACK_SET_MODEL = "set-model:";
  private static final String CALLBACK_SET_MODE = "set-mode:";
  private static final String CALLBACK_NEW_DIALOG = "action:new";

  private static final String DEFAULT_MODE = "default";

  private final TelegramWebhookBotAdapter webhookBot;
  private final SyncChatService syncChatService;
  private final ChatProviderService chatProviderService;
  private final TelegramChatStateStore stateStore;

  public TelegramChatService(
      TelegramWebhookBotAdapter webhookBot,
      SyncChatService syncChatService,
      ChatProviderService chatProviderService,
      TelegramChatStateStore stateStore) {
    this.webhookBot = webhookBot;
    this.syncChatService = syncChatService;
    this.chatProviderService = chatProviderService;
    this.stateStore = stateStore;
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
    String command = rawCommand != null ? rawCommand.trim().toLowerCase() : "";
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
        stateStore.compute(chatId, () -> ensureState(chatId), TelegramChatState::resetSession);
        sendText(chatId, "Начинаем новый диалог. Чем могу помочь?");
      }
      case COMMAND_MENU -> sendMainMenu(chatId, ensureState(chatId));
      default -> sendText(chatId, "Неизвестная команда. Доступны /start, /new и /menu.");
    }
  }

  private void processUserPrompt(long chatId, String prompt) {
    TelegramChatState state = ensureState(chatId);
    ChatSyncRequest request = buildSyncRequest(state, prompt);

    CompletableFuture.runAsync(
        () -> {
          try {
            SyncChatService.SyncChatResult result = syncChatService.sync(request);
            UUID nextSessionId = result.context().sessionId();
            stateStore.compute(
                chatId,
                () -> state,
                current ->
                    current == null ? state : current.withSessionId(nextSessionId));

            if (result.context().newSession()) {
              sendText(
                  chatId,
                  "Создан новый диалог (sessionId: " + nextSessionId + "). Продолжаю ответ…");
            }

            sendText(chatId, sanitizeResponse(result.response().content()));

            if (result.response().usage() != null) {
              sendUsageSummary(chatId, result);
            }
          } catch (ResponseStatusException apiError) {
            log.warn("LLM request failed: {}", apiError.getMessage());
            sendText(
                chatId,
                "Не удалось получить ответ от модели ("
                    + apiError.getStatusCode()
                    + "). Попробуйте позже.");
          } catch (Exception ex) {
            log.error("Unexpected error during sync call: {}", ex.getMessage(), ex);
            sendText(chatId, "Произошла ошибка при обработке запроса. Попробуйте позже.");
          }
        });
  }

  private ChatSyncRequest buildSyncRequest(TelegramChatState state, String prompt) {
    ChatStreamRequestOptions options = null;
    if (state.hasSamplingOverrides()) {
      options =
          new ChatStreamRequestOptions(
              state.temperatureOverride(), state.topPOverride(), state.maxTokensOverride());
    }

    List<String> toolCodes =
        CollectionUtils.isEmpty(state.requestedToolCodes())
            ? null
            : List.copyOf(state.requestedToolCodes());

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
      if (data.startsWith(CALLBACK_SET_PROVIDER)) {
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
        String providerId = data.substring(CALLBACK_MENU_MODELS.length());
        sendModelMenu(chatId, providerId);
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_MENU_MODE.equals(data)) {
        sendModeMenu(chatId);
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_MENU_MAIN.equals(data)) {
        sendMainMenu(chatId, ensureState(chatId));
        respondToCallback(callbackQuery.getId(), null, false);
      } else if (CALLBACK_NEW_DIALOG.equals(data)) {
        stateStore.compute(chatId, () -> ensureState(chatId), TelegramChatState::resetSession);
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
        log.debug("Failed to send callback error notification");
      }
    }
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

  private void sendUsageSummary(long chatId, SyncChatService.SyncChatResult result) {
    var usage = result.response().usage();
    var cost = result.response().cost();
    StringBuilder builder = new StringBuilder("Usage: ");
    if (usage != null) {
      builder
          .append("prompt=")
          .append(usage.promptTokens() != null ? usage.promptTokens() : "?")
          .append(", completion=")
          .append(usage.completionTokens() != null ? usage.completionTokens() : "?")
          .append(", total=")
          .append(usage.totalTokens() != null ? usage.totalTokens() : "?");
    }
    if (cost != null) {
      builder
          .append("; cost=")
          .append(cost.total() != null ? cost.total() : "?")
          .append(" ")
          .append(cost.currency() != null ? cost.currency() : "");
    }
    sendText(chatId, builder.toString());
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
                        .text("Новый диалог")
                        .callbackData(CALLBACK_NEW_DIALOG)
                        .build())));

    String summary =
        "Текущие настройки:\n"
            + "• Провайдер: "
            + state.providerId()
            + '\n'
            + "• Модель: "
            + state.modelId()
            + '\n'
            + "• Режим: "
            + (state.interactionMode().equalsIgnoreCase("research") ? "research" : "default");
    if (state.sessionId() != null) {
      summary += "\n• sessionId: " + state.sessionId();
    }

    sendMessage(chatId, summary, markup);
  }

  private void sendProvidersMenu(long chatId) {
    Map<String, ChatProvidersProperties.Provider> providers = chatProviderService.providers();
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    providers.forEach(
        (id, provider) ->
            rows.add(
                List.of(
                    InlineKeyboardButton.builder()
                        .text(provider.getDisplayName() != null ? provider.getDisplayName() : id)
                        .callbackData(CALLBACK_SET_PROVIDER + id)
                        .build())));
    rows.add(
        List.of(
            InlineKeyboardButton.builder()
                .text("← Назад")
                .callbackData(CALLBACK_MENU_MAIN)
                .build()));

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
    rows.add(
        List.of(
            InlineKeyboardButton.builder()
                .text("← Назад")
                .callbackData(CALLBACK_MENU_MAIN)
                .build()));

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
                List.of(
                    InlineKeyboardButton.builder()
                        .text("← Назад")
                        .callbackData(CALLBACK_MENU_MAIN)
                        .build())));

    sendMessage(chatId, "Выберите режим работы:", markup);
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
        () -> ensureState(chatId).withInteractionMode(mode.name().toLowerCase()),
        current ->
            (current != null ? current : ensureState(chatId))
                .withInteractionMode(mode.name().toLowerCase())
                .resetSession());

    respondToCallback(callbackId, "Режим обновлён", false);
    sendMainMenu(chatId, ensureState(chatId));
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
}
