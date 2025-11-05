package com.aiadvent.backend.telegram.config;

import com.aiadvent.backend.telegram.bot.TelegramBotLifecycle;
import com.aiadvent.backend.telegram.bot.TelegramLongPollingBotAdapter;
import com.aiadvent.backend.telegram.service.TelegramUpdateHandler;
import java.time.Duration;
import java.util.List;
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
    DefaultBotOptions options = new DefaultBotOptions();
    options.setGetUpdatesLimit(properties.getPolling().getLimit());
    options.setGetUpdatesTimeout((int) toSeconds(properties.getPolling().getTimeout()));
    List<String> allowedUpdates = properties.getAllowedUpdates();
    if (!allowedUpdates.isEmpty()) {
      options.setAllowedUpdates(List.copyOf(allowedUpdates));
    }
    return options;
  }

  @Bean
  @ConditionalOnMissingBean
  public TelegramLongPollingBotAdapter telegramLongPollingBotAdapter(
      DefaultBotOptions telegramBotOptions,
      TelegramBotProperties properties,
      TelegramUpdateHandler updateHandler) {
    return new TelegramLongPollingBotAdapter(telegramBotOptions, properties, updateHandler);
  }

  @Bean
  @ConditionalOnMissingBean
  public TelegramBotLifecycle telegramBotLifecycle(
      TelegramBotProperties properties,
      TelegramLongPollingBotAdapter longPollingBot) {
    return new TelegramBotLifecycle(properties, longPollingBot);
  }

  private long toSeconds(Duration duration) {
    return duration != null ? Math.max(0L, duration.toSeconds()) : 0L;
  }
}
