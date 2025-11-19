package com.aiadvent.backend.profile.oauth;

import com.fasterxml.jackson.databind.JsonNode;

public record OAuthUserInfo(
    String providerId,
    String externalId,
    String displayName,
    String email,
    JsonNode attributes) {}
