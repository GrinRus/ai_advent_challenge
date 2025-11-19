package com.aiadvent.backend.profile.service;

import com.fasterxml.jackson.databind.JsonNode;
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
    String metadataLine = formatMetadata(profile.metadata());
    if (StringUtils.hasText(metadataLine)) {
      joiner.add(metadataLine);
    }
    return joiner.toString();
  }

  private String safe(String value) {
    return StringUtils.hasText(value) ? value : "the user";
  }

  private String formatMetadata(JsonNode metadata) {
    if (metadata == null || metadata.isMissingNode() || metadata.isNull() || !metadata.isObject()) {
      return "";
    }
    StringJoiner details = new StringJoiner("; ");
    metadata.fields()
        .forEachRemaining(
            entry -> {
              JsonNode value = entry.getValue();
              if (value == null || value.isNull()) {
                return;
              }
              if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                details.add(entry.getKey() + "=" + value.asText());
              } else {
                details.add(entry.getKey() + "=" + value.toString());
              }
            });
    String rendered = details.toString();
    if (!StringUtils.hasText(rendered)) {
      return "";
    }
    return "Additional profile metadata: " + rendered;
  }
}
