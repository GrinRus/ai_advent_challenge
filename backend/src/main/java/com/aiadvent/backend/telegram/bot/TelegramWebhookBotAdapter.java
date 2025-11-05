package com.aiadvent.backend.telegram.bot;

import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import com.aiadvent.backend.telegram.service.TelegramUpdateHandler;
import java.util.Objects;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

public class TelegramWebhookBotAdapter extends TelegramWebhookBot {

  private final TelegramBotProperties properties;
  private final TelegramUpdateHandler updateHandler;

  public TelegramWebhookBotAdapter(
      TelegramBotProperties properties, TelegramUpdateHandler updateHandler) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.updateHandler = Objects.requireNonNull(updateHandler, "updateHandler");
  }

  @Override
  public String getBotToken() {
    return properties.getBot().getToken();
  }

  @Override
  public String getBotUsername() {
    return properties.getBot().getUsername();
  }

  @Override
  public String getBotPath() {
    return properties.getWebhook().getPath();
  }

  @Override
  public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
    updateHandler.handle(update);
    return null;
  }
}
