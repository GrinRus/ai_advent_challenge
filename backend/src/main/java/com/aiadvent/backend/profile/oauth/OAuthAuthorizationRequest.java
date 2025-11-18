package com.aiadvent.backend.profile.oauth;

import java.util.List;

public record OAuthAuthorizationRequest(
    String providerId,
    String redirectUri,
    String state,
    String codeChallenge,
    String codeChallengeMethod,
    List<String> scopes,
    String deviceId) {}
