package com.aiadvent.backend.telegram.config;

import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import com.aiadvent.backend.telegram.service.TelegramUpdateHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.DefaultBotOptions;

@Configuration
@EnableConfigurationProperties(TelegramBotProperties.class)
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class TelegramBotConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DefaultBotOptions telegramBotOptions(TelegramBotProperties properties) {
    return new DefaultBotOptions();
  }

  @Bean
  @ConditionalOnMissingBean
  public TelegramWebhookBotAdapter telegramWebhookBotAdapter(
      DefaultBotOptions telegramBotOptions,
      TelegramBotProperties properties,
      TelegramUpdateHandler updateHandler) {
    return new TelegramWebhookBotAdapter(telegramBotOptions, properties, updateHandler);
  }
}
