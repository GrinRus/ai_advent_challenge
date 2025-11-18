package com.aiadvent.backend.profile.api;

import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.service.ProfileUpdateCommand;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProfileUpdateRequest(
    @Size(max = 255) String displayName,
    @Size(max = 16) String locale,
    @Size(max = 64) String timezone,
    UserProfile.CommunicationMode communicationMode,
    List<@Size(max = 128) String> habits,
    List<@Size(max = 128) String> antiPatterns,
    JsonNode workHours,
    JsonNode metadata,
    @Valid List<ChannelOverrideRequest> channelOverrides) {

  public ProfileUpdateCommand toCommand() {
    return new ProfileUpdateCommand(
        displayName,
        locale,
        timezone,
        communicationMode,
        habits,
        antiPatterns,
        workHours,
        metadata,
        channelOverrides != null
            ? channelOverrides.stream()
                .map(ChannelOverrideRequest::toCommand)
                .toList()
            : List.of());
  }

  public record ChannelOverrideRequest(
      @Size(max = 64) String channel, JsonNode settings) {
    public ProfileUpdateCommand.ChannelSettingsCommand toCommand() {
      return new ProfileUpdateCommand.ChannelSettingsCommand(channel, settings);
    }
  }
}
