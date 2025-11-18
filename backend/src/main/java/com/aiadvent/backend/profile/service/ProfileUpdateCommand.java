package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ProfileUpdateCommand(
    String displayName,
    String locale,
    String timezone,
    UserProfile.CommunicationMode communicationMode,
    List<String> habits,
    List<String> antiPatterns,
    JsonNode workHours,
    JsonNode metadata,
    List<ChannelSettingsCommand> channelOverrides) {

  public record ChannelSettingsCommand(String channel, JsonNode settings) {}
}
