package com.aiadvent.backend.telegram.web;

import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import com.aiadvent.backend.telegram.config.TelegramBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

@RestController
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
@RequestMapping("${app.telegram.webhook.path:/telegram/update}")
public class TelegramWebhookController {

  private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);
  private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

  private final TelegramBotProperties properties;
  private final TelegramWebhookBotAdapter webhookBot;

  public TelegramWebhookController(
      TelegramBotProperties properties, TelegramWebhookBotAdapter webhookBot) {
    this.properties = properties;
    this.webhookBot = webhookBot;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> handleUpdate(
      @RequestBody Update update,
      @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret) {
    String expectedSecret = properties.getWebhook().getSecretToken();
    if (StringUtils.hasText(expectedSecret)) {
      if (!StringUtils.hasText(providedSecret) || !expectedSecret.equals(providedSecret)) {
        log.warn("Rejected Telegram webhook update due to secret token mismatch");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
    }

    BotApiMethod<?> response = webhookBot.onWebhookUpdateReceived(update);
    if (response == null) {
      return ResponseEntity.ok().build();
    }
    return ResponseEntity.ok(response);
  }
}
