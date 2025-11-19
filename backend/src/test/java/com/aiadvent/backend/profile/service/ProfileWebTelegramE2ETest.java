package com.aiadvent.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.BackendApplication;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.service.ConversationContext;
import com.aiadvent.backend.chat.service.SyncChatService;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.persistence.UserProfileRepository;
import com.aiadvent.backend.profile.service.IdentityCommand;
import com.aiadvent.backend.support.SingletonPostgresContainer;
import com.aiadvent.backend.telegram.bot.TelegramWebhookBotAdapter;
import com.aiadvent.backend.telegram.service.TelegramChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = BackendApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.profile.dev.enabled=true",
      "app.profile.dev.token=e2e-token",
      "app.telegram.enabled=true",
      "app.telegram.webhook.secret-token=test-secret",
      "app.telegram.webhook.path=/telegram/update",
      "app.profile.cache.redis-enabled=true",
      "PROFILE_CACHE_REDIS_ENABLED=true"
    })
@Testcontainers
class ProfileWebTelegramE2ETest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine")
          .withExposedPorts(6379)
          .waitingFor(Wait.forListeningPort());

  static {
    System.setProperty("spring.ai.mcp.client.enabled", "false");
    SingletonPostgresContainer.getInstance();
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SingletonPostgresContainer.getInstance()::getJdbcUrl);
    registry.add("spring.datasource.username", SingletonPostgresContainer.getInstance()::getUsername);
    registry.add("spring.datasource.password", SingletonPostgresContainer.getInstance()::getPassword);
    registry.add("spring.liquibase.url", SingletonPostgresContainer.getInstance()::getJdbcUrl);
    registry.add("spring.liquibase.user", SingletonPostgresContainer.getInstance()::getUsername);
    registry.add("spring.liquibase.password", SingletonPostgresContainer.getInstance()::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private UserProfileRepository userProfileRepository;

  @Autowired private UserProfileService userProfileService;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private TelegramChatService telegramChatService;

  @MockBean private SyncChatService syncChatService;

  @MockBean private TelegramWebhookBotAdapter webhookBotAdapter;

  @BeforeEach
  void setup() {
    userProfileRepository.deleteAll();
    Mockito.reset(syncChatService, webhookBotAdapter);
    ConversationContext context =
        new ConversationContext(UUID.randomUUID(), true, UUID.randomUUID());
    ChatSyncResponse syncResponse =
        new ChatSyncResponse(
            UUID.randomUUID(),
            "Ответ",
            new StructuredSyncProvider("openai", "gpt-4o-mini"),
            null,
            null,
            new StructuredSyncUsageStats(100, 150, 250),
            new UsageCostDetails(
                new BigDecimal("0.001"), new BigDecimal("0.002"), new BigDecimal("0.003"), "USD"),
            300L,
            Instant.now());
    Mockito.when(syncChatService.sync(Mockito.any()))
        .thenReturn(new SyncChatService.SyncChatResult(context, syncResponse));
    Mockito.doAnswer(
            invocation -> {
              Update update = invocation.getArgument(0);
              telegramChatService.handle(update);
              return null;
            })
        .when(webhookBotAdapter)
        .onWebhookUpdateReceived(Mockito.any(Update.class));
  }

  @Test
  void webUpdatePropagatesToTelegramAndCacheEvictionWorks() throws Exception {
    HttpHeaders headers = buildProfileHeaders("web", "e2e", "web");
    ProfileUpdateRequest updateRequest =
        new ProfileUpdateRequest(
            "E2E User",
            "ru",
            "Europe/Moscow",
            UserProfile.CommunicationMode.TEXT,
            List.of("prefers summaries"),
            List.of("avoid smalltalk"),
            null,
            null,
            null);

    ResponseEntity<UserProfileDocument> response =
        restTemplate.exchange(
            "/api/profile/{namespace}/{reference}",
            org.springframework.http.HttpMethod.PUT,
            new HttpEntity<>(updateRequest, headers),
            UserProfileDocument.class,
            "web",
            "e2e");

    assertThat(response.getStatusCode().is2xxSuccessful())
        .withFailMessage(
            "Profile update failed with status %s and body %s",
            response.getStatusCode(), response.getBody())
        .isTrue();

    userProfileService.attachIdentity(
        new ProfileLookupKey("web", "e2e", "web"),
        new IdentityCommand(
            "telegram",
            "42",
            objectMapper.createObjectNode().put("username", "tg-e2e"),
            List.of("write")));

    Update telegramUpdate = createTelegramUpdate(777L, 42L, "Привет!");
    HttpHeaders webhookHeaders = new HttpHeaders();
    webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
    webhookHeaders.add("X-Telegram-Bot-Api-Secret-Token", "test-secret");
    restTemplate.postForEntity(
        "/telegram/update",
        new HttpEntity<>(objectMapper.writeValueAsString(telegramUpdate), webhookHeaders),
        Void.class);

    ArgumentCaptor<ChatSyncRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatSyncRequest.class);
    Mockito.verify(syncChatService, Mockito.timeout(2000)).sync(requestCaptor.capture());

    UserProfileDocument telegramProfile =
        userProfileService.resolveProfile(new ProfileLookupKey("telegram", "42", "telegram"));
    assertThat(telegramProfile.displayName()).isEqualTo("E2E User");

    ProfileUpdateCommand secondUpdate =
        new ProfileUpdateCommand(
            "E2E User 2",
            "en",
            "UTC",
            UserProfile.CommunicationMode.TEXT,
            List.of("prefers summaries"),
            List.of("avoid smalltalk"),
            null,
            null,
            null);
    userProfileService.updateProfile(
        new ProfileLookupKey("web", "e2e", "web"),
        secondUpdate,
        "W/\"" + response.getBody().version() + "\"");

    userProfileService.evict(new ProfileLookupKey("telegram", "42", "telegram"));
    UserProfileDocument refreshed =
        userProfileService.resolveProfile(new ProfileLookupKey("telegram", "42", "telegram"));
    assertThat(refreshed.displayName()).isEqualTo("E2E User 2");
  }

  private HttpHeaders buildProfileHeaders(String namespace, String reference, String channel) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Content-Type", "application/json");
    headers.add("X-Profile-Key", namespace + ":" + reference);
    headers.add("X-Profile-Channel", channel);
    headers.add("X-Profile-Auth", "e2e-token");
    headers.add("If-Match", "W/\"0\"");
    return headers;
  }

  private Update createTelegramUpdate(long chatId, long userId, String text) {
    User user = new User();
    user.setId(userId);
    Chat chat = new Chat();
    chat.setId(chatId);
    Message message = new Message();
    message.setChat(chat);
    message.setText(text);
    message.setFrom(user);
    message.setMessageId(1);
    Update update = new Update();
    update.setUpdateId((int) UUID.randomUUID().getMostSignificantBits());
    update.setMessage(message);
    return update;
  }

  record ProfileUpdateRequest(
      String displayName,
      String locale,
      String timezone,
      UserProfile.CommunicationMode communicationMode,
      List<String> habits,
      List<String> antiPatterns,
      Object workHours,
      Object metadata,
      List<UserProfileDocument.UserChannelDocument> channelOverrides) {}
}
