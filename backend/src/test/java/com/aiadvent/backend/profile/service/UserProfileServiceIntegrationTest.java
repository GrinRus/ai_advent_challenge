package com.aiadvent.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.service.ProfileUpdateCommand.ChannelSettingsCommand;
import com.aiadvent.backend.profile.service.UserProfileDocument.UserIdentityDocument;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class UserProfileServiceIntegrationTest extends PostgresTestContainer {

  @Autowired private UserProfileService userProfileService;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void resolveProfileCreatesEntityWithDefaults() {
    ProfileLookupKey key = new ProfileLookupKey("web", "svc-integration", "web");

    UserProfileDocument first = userProfileService.resolveProfile(key);
    UserProfileDocument second = userProfileService.resolveProfile(key);

    assertThat(first.profileId()).isEqualTo(second.profileId());
    assertThat(first.displayName()).isEqualTo("svc-integration");
    assertThat(first.locale()).isEqualTo("en");
    assertThat(first.habits()).isEmpty();
  }

  @Test
  void updateProfileRequiresMatchingEtag() {
    ProfileLookupKey key = new ProfileLookupKey("web", "etag-check", "web");
    UserProfileDocument original = userProfileService.resolveProfile(key);

    ObjectNode workHours = objectMapper.createObjectNode().put("timezone", "Europe/Moscow");
    ObjectNode metadata = objectMapper.createObjectNode().put("notes", "Integration");
    ChannelSettingsCommand override =
        new ChannelSettingsCommand("telegram", objectMapper.createObjectNode().put("reply", "short"));
    ProfileUpdateCommand updateCommand =
        new ProfileUpdateCommand(
            "Integration User",
            "ru",
            "Europe/Moscow",
            UserProfile.CommunicationMode.HYBRID,
            List.of("morning-summary"),
            List.of("avoid_meetings"),
            workHours,
            metadata,
            List.of(override));

    assertThatThrownBy(() -> userProfileService.updateProfile(key, updateCommand, "\"999\""))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    String etag = "W/\"" + original.version() + "\"";
    UserProfileDocument updated = userProfileService.updateProfile(key, updateCommand, etag);
    assertThat(updated.displayName()).isEqualTo("Integration User");
    assertThat(updated.locale()).isEqualTo("ru");
    assertThat(updated.channels()).anySatisfy(channel -> assertThat(channel.channel()).isEqualTo("telegram"));
  }

  @Test
  void attachIdentityAddsAndRemovesIdentity() {
    ProfileLookupKey key = new ProfileLookupKey("web", "identity-case", "web");
    userProfileService.resolveProfile(key);

    ObjectNode attributes = objectMapper.createObjectNode().put("username", "demo");
    IdentityCommand telegramCommand = new IdentityCommand("telegram", "12345", attributes, List.of("write"));

    UserProfileDocument withIdentity = userProfileService.attachIdentity(key, telegramCommand);
    assertThat(withIdentity.identities())
        .hasSize(1)
        .map(UserIdentityDocument::provider)
        .containsExactly("telegram");

    assertThatThrownBy(() -> userProfileService.attachIdentity(key, telegramCommand))
        .isInstanceOf(IllegalStateException.class);

    UserProfileDocument withoutIdentity =
        userProfileService.detachIdentity(key, "telegram", "12345");
    assertThat(withoutIdentity.identities()).isEmpty();
  }
}
