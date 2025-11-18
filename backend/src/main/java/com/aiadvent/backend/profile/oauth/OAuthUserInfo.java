package com.aiadvent.backend.profile.oauth;

import java.util.Map;

public record OAuthUserInfo(
    String providerId,
    String externalId,
    String displayName,
    String email,
    Map<String, Object> attributes) {}
