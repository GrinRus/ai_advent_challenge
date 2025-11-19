package com.aiadvent.backend.profile.config;

import com.aiadvent.backend.profile.service.ProfileChangePublisher;
import com.aiadvent.backend.profile.service.ProfileChangedEvent;
import com.aiadvent.backend.profile.service.RedisProfileChangePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties({
  ProfileCacheProperties.class,
  ProfileDevAuthProperties.class,
  ProfilePromptProperties.class,
  OAuthProviderProperties.class
})
public class ProfileConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ProfileConfiguration.class);

  @Bean
  @ConditionalOnProperty(
      prefix = "app.profile.cache",
      name = "redis-enabled",
      havingValue = "true")
  public ProfileChangePublisher redisProfileChangePublisher(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProfileCacheProperties cacheProperties) {
    return new RedisProfileChangePublisher(redisTemplate, objectMapper, cacheProperties);
  }

  @Bean
  @ConditionalOnMissingBean(ProfileChangePublisher.class)
  public ProfileChangePublisher profileChangePublisherFallback(
      ApplicationEventPublisher eventPublisher) {
    return eventPublisher::publishEvent;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.profile.cache",
      name = "redis-enabled",
      havingValue = "true")
  public RedisMessageListenerContainer profileRedisListener(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProfileCacheProperties cacheProperties,
      ApplicationEventPublisher eventPublisher) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(redisTemplate.getConnectionFactory());
    ChannelTopic topic = new ChannelTopic(cacheProperties.getEventChannel());
    container.addMessageListener(
        new ProfileMessageListener(objectMapper, eventPublisher), topic);
    return container;
  }

  private static final class ProfileMessageListener implements MessageListener {
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private ProfileMessageListener(
        ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
      this.objectMapper = objectMapper;
      this.eventPublisher = eventPublisher;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
      try {
        ProfileChangedEvent event =
            objectMapper.readValue(message.getBody(), ProfileChangedEvent.class);
        eventPublisher.publishEvent(event);
      } catch (Exception e) {
        log.warn("Failed to deserialize profile change event: {}", e.getMessage());
      }
    }
  }
}
