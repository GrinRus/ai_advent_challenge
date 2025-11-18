package com.aiadvent.backend.profile.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record IdentityCommand(
    String provider, String externalId, JsonNode attributes, List<String> scopes) {}
