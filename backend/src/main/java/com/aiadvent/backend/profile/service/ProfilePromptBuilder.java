package com.aiadvent.backend.profile.service;

import java.util.Locale;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProfilePromptBuilder {

  public String buildPersonaSnippet(UserProfileDocument profile) {
    if (profile == null) {
      return "";
    }
    StringJoiner joiner = new StringJoiner("\n");
    joiner.add("You are interacting with " + safe(profile.displayName()) + ".");
    if (StringUtils.hasText(profile.locale())) {
      joiner.add("Preferred language: " + profile.locale());
    }
    if (StringUtils.hasText(profile.timezone())) {
      joiner.add("User timezone: " + profile.timezone());
    }
    if (profile.communicationMode() != null) {
      joiner.add(
          "Preferred communication mode: "
              + profile.communicationMode().name().toLowerCase(Locale.ROOT));
    }
    if (profile.habits() != null && !profile.habits().isEmpty()) {
      joiner.add("User habits: " + String.join(", ", profile.habits()));
    }
    if (profile.antiPatterns() != null && !profile.antiPatterns().isEmpty()) {
      joiner.add("Avoid the following anti-patterns: " + String.join(", ", profile.antiPatterns()));
    }
    return joiner.toString();
  }

  private String safe(String value) {
    return StringUtils.hasText(value) ? value : "the user";
  }
}
