package com.aiadvent.backend.telegram.bot;

import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import com.aiadvent.backend.telegram.config.TelegramBotProperties.Mode;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class TelegramBotLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(TelegramBotLifecycle.class);

  private final TelegramBotProperties properties;
  private final TelegramLongPollingBotAdapter longPollingBot;

  private volatile boolean running;
  private BotSession botSession;

  public TelegramBotLifecycle(
      TelegramBotProperties properties,
      TelegramLongPollingBotAdapter longPollingBot) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.longPollingBot = Objects.requireNonNull(longPollingBot, "longPollingBot");
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }

    try {
      Mode mode = properties.getMode();
      if (mode == Mode.WEBHOOK) {
        log.warn("Webhook mode is not fully supported yet; falling back to long polling.");
      }
      registerLongPollingBot();
      running = true;
      log.info("Telegram bot registered in {} mode", mode);
    } catch (TelegramApiException ex) {
      throw new IllegalStateException("Failed to register Telegram bot", ex);
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    try {
      if (botSession != null) {
        botSession.stop();
      }
      longPollingBot.onClosing();
    } finally {
      running = false;
      botSession = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void registerLongPollingBot() throws TelegramApiException {
    TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
    botSession = botsApi.registerBot(longPollingBot);
  }
}
