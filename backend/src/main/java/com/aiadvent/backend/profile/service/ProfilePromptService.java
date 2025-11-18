package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.config.ProfilePromptProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProfilePromptService {

  private final UserProfileService userProfileService;
  private final ProfilePromptBuilder promptBuilder;
  private final ProfilePromptProperties properties;

  public ProfilePromptService(
      UserProfileService userProfileService,
      ProfilePromptBuilder promptBuilder,
      ProfilePromptProperties properties) {
    this.userProfileService = userProfileService;
    this.promptBuilder = promptBuilder;
    this.properties = properties;
  }

  public Optional<String> personaSnippet() {
    if (!properties.isEnabled()) {
      return Optional.empty();
    }
    return ProfileContextHolder.current()
        .map(userProfileService::resolveProfile)
        .map(promptBuilder::buildPersonaSnippet)
        .filter(StringUtils::hasText);
  }
}
