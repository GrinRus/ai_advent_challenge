package com.aiadvent.backend.telegram.bot;

import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class TelegramBotLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(TelegramBotLifecycle.class);

  private final TelegramBotProperties properties;
  private final TelegramWebhookBotAdapter webhookBot;

  private volatile boolean running;

  public TelegramBotLifecycle(
      TelegramBotProperties properties, TelegramWebhookBotAdapter webhookBot) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.webhookBot = Objects.requireNonNull(webhookBot, "webhookBot");
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }

    try {
      registerWebhook();
      running = true;
      log.info("Telegram webhook registered at {}", resolveWebhookUrl());
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
      webhookBot.execute(new DeleteWebhook());
    } catch (TelegramApiException ex) {
      log.warn("Failed to delete Telegram webhook: {}", ex.getMessage());
    } finally {
      running = false;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void registerWebhook() throws TelegramApiException {
    String webhookUrl = resolveWebhookUrl();
    if (!StringUtils.hasText(webhookUrl)) {
      throw new IllegalStateException("Webhook URL must be configured (app.telegram.webhook.external-url)");
    }

    SetWebhook.SetWebhookBuilder builder = SetWebhook.builder().url(webhookUrl);

    List<String> allowedUpdates = properties.getAllowedUpdates();
    if (allowedUpdates != null && !allowedUpdates.isEmpty()) {
      builder.allowedUpdates(List.copyOf(allowedUpdates));
    }

    String secretToken = properties.getWebhook().getSecretToken();
    if (StringUtils.hasText(secretToken)) {
      builder.secretToken(secretToken);
    }

    webhookBot.setWebhook(builder.build());
  }

  private String resolveWebhookUrl() {
    String externalUrl = properties.getWebhook().getExternalUrl();
    String path = properties.getWebhook().getPath();
    if (!StringUtils.hasText(externalUrl)) {
      return null;
    }
    if (!StringUtils.hasText(path)) {
      return externalUrl;
    }
    if (externalUrl.endsWith("/")) {
      return path.startsWith("/") ? externalUrl + path.substring(1) : externalUrl + path;
    }
    return path.startsWith("/") ? externalUrl + path : externalUrl + '/' + path;
  }
}
