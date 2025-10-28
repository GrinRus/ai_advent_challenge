package com.aiadvent.backend.chat.config;

import com.aiadvent.backend.chat.logging.ChatLoggingSupport;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@EnableConfigurationProperties(ChatLoggingProperties.class)
public class ChatLoggingConfiguration {

  @Bean
  public ChatLoggingSupport chatLoggingSupport(ChatLoggingProperties properties) {
    return new ChatLoggingSupport(properties);
  }

  @Bean
  @Primary
  @ConditionalOnBean(ToolCallingManager.class)
  public ToolCallingManager loggingToolCallingManager(
      @Qualifier("toolCallingManager") ToolCallingManager delegate, ChatLoggingSupport loggingSupport) {
    return loggingSupport.decorateToolCallingManager(delegate);
  }
}
