package com.aiadvent.backend.profile.api;

import com.aiadvent.backend.profile.service.IdentityCommand;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record IdentityRequest(
    @NotBlank @Size(max = 64) String provider,
    @NotBlank @Size(max = 128) String externalId,
    JsonNode attributes,
    List<@Size(max = 64) String> scopes) {

  public IdentityCommand toCommand() {
    return new IdentityCommand(provider, externalId, attributes, scopes);
  }
}
