package com.aiadvent.backend.telegram.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class LoggingTelegramUpdateHandler implements TelegramUpdateHandler {

  private static final Logger log = LoggerFactory.getLogger(LoggingTelegramUpdateHandler.class);

  @Override
  public void handle(Update update) {
    if (update == null) {
      return;
    }

    if (update.hasMessage()) {
      Message message = update.getMessage();
      log.info(
          "Received Telegram message from {} ({}): {}",
          message.getFrom() != null ? message.getFrom().getId() : "unknown",
          message.getChat() != null ? message.getChat().getId() : "unknown",
          message.hasText() ? message.getText() : "<non-text message>");
      return;
    }

    if (update.hasCallbackQuery()) {
      CallbackQuery callback = update.getCallbackQuery();
      Long chatId = null;
      if (callback.getMessage() instanceof Message message) {
        chatId = message.getChatId();
      }

      log.info(
          "Received Telegram callback query {} from chat {}",
          callback.getId(),
          chatId != null ? chatId : callback.getChatInstance());
      return;
    }

    log.info("Received Telegram update of type {}", update);
  }
}
