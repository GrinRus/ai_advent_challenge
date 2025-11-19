package com.aiadvent.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.profile.config.ProfileCacheProperties;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class UserProfileCacheRedisIntegrationTest extends PostgresTestContainer {

  @BeforeAll
  static void enableRedis() {
    setRedisEnabled(true);
  }

  @AfterAll
  static void disableRedis() {
    setRedisEnabled(false);
  }

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine")
          .withExposedPorts(6379)
          .waitingFor(Wait.forListeningPort());

  @DynamicPropertySource
  static void configureRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("app.profile.cache.redis-enabled", () -> "true");
    registry.add("PROFILE_CACHE_REDIS_ENABLED", () -> "true");
  }

  @Autowired private UserProfileService userProfileService;

  @Autowired private StringRedisTemplate redisTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private ProfileCacheProperties cacheProperties;

  @Test
  void redisEventEvictsLocalCache() throws Exception {
    ProfileLookupKey key = new ProfileLookupKey("web", "redis-event", "web");
    UserProfileDocument document = userProfileService.resolveProfile(key);
    assertThat(userProfileService.findProfile(key)).isPresent();

    ProfileChangedEvent event =
        new ProfileChangedEvent(
            document.profileId(), key.normalizedNamespace(), key.normalizedReference(), Instant.now());
    long listeners =
        redisTemplate.convertAndSend(
            cacheProperties.getEventChannel(), objectMapper.writeValueAsString(event));
    assertThat(listeners)
        .withFailMessage("No Redis listeners subscribed to %s", cacheProperties.getEventChannel())
        .isGreaterThan(0);

    assertThat(waitForEviction(key)).isTrue();
  }

  private boolean waitForEviction(ProfileLookupKey key) throws InterruptedException {
    for (int attempt = 0; attempt < 30; attempt++) {
      if (userProfileService.findProfile(key).isEmpty()) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return false;
  }
}
