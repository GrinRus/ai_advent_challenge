package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.config.ProfileCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisProfileChangePublisher implements ProfileChangePublisher {

  private static final Logger log = LoggerFactory.getLogger(RedisProfileChangePublisher.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final String channel;

  public RedisProfileChangePublisher(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProfileCacheProperties cacheProperties) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.channel = cacheProperties.getRedisPrefix() + "events";
  }

  @Override
  public void publish(ProfileChangedEvent event) {
    if (event == null) {
      return;
    }
    try {
      String payload = objectMapper.writeValueAsString(event);
      redisTemplate.convertAndSend(channel, payload);
    } catch (JsonProcessingException ex) {
      log.warn("Failed to serialize profile change event {}", event.profileId(), ex);
    } catch (RuntimeException ex) {
      log.warn("Failed to publish profile change event {}", event.profileId(), ex);
    }
  }
}
