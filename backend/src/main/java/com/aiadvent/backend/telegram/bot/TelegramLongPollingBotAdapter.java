package com.aiadvent.backend.telegram.bot;

import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import com.aiadvent.backend.telegram.service.TelegramUpdateHandler;
import java.util.Objects;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

public class TelegramLongPollingBotAdapter extends TelegramLongPollingBot {

  private final TelegramBotProperties properties;
  private final TelegramUpdateHandler updateHandler;

  public TelegramLongPollingBotAdapter(
      DefaultBotOptions options,
      TelegramBotProperties properties,
      TelegramUpdateHandler updateHandler) {
    super(options);
    this.properties = Objects.requireNonNull(properties, "properties");
    this.updateHandler = Objects.requireNonNull(updateHandler, "updateHandler");
  }

  @Override
  public void onUpdateReceived(Update update) {
    updateHandler.handle(update);
  }

  @Override
  public String getBotUsername() {
    return properties.getBot().getUsername();
  }

  @Override
  public String getBotToken() {
    return properties.getBot().getToken();
  }
}
