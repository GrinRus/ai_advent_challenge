package com.aiadvent.backend.telegram.service;

import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.service.SyncChatService;
import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import java.util.List;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Component
public class TelegramChatService implements TelegramUpdateHandler {

  private static final Logger log = LoggerFactory.getLogger(TelegramChatService.class);

  private static final String COMMAND_START = "/start";
  private static final String COMMAND_NEW = "/new";
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
                + ".\nКоманда /new сбрасывает текущий диалог.");
      }
      case COMMAND_NEW -> {
        stateStore.compute(chatId, () -> ensureState(chatId), TelegramChatState::resetSession);
        sendText(chatId, "Начинаем новый диалог. Чем могу помочь?");
      }
      default -> sendText(chatId, "Неизвестная команда. Доступны /start и /new.");
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
                    + "). Попробуйте позже." );
          } catch (Exception ex) {
            log.error("Unexpected error during sync call: {}", ex.getMessage(), ex);
            sendText(
                chatId,
                "Произошла ошибка при обработке запроса. Попробуйте повторить позже.");
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
    if (callbackQuery == null) {
      return;
    }
    AnswerCallbackQuery answer =
        AnswerCallbackQuery.builder()
            .callbackQueryId(callbackQuery.getId())
            .text("Инлайн-кнопки пока не поддерживаются.")
            .showAlert(false)
            .build();
    try {
      webhookBot.execute(answer);
    } catch (TelegramApiException ex) {
      log.warn("Failed to answer callback query: {}", ex.getMessage());
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
    SendMessage message =
        SendMessage.builder().chatId(Long.toString(chatId)).text(text).disableWebPagePreview(true).build();
    try {
      webhookBot.execute(message);
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
}
