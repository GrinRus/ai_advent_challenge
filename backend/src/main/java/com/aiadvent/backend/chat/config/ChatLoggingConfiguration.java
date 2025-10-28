package com.aiadvent.backend.chat.config;

import com.aiadvent.backend.chat.logging.ChatLoggingSupport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatLoggingProperties.class)
public class ChatLoggingConfiguration {

  @Bean
  public ChatLoggingSupport chatLoggingSupport(ChatLoggingProperties properties) {
    return new ChatLoggingSupport(properties);
  }
}
