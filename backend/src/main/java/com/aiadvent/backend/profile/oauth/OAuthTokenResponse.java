package com.aiadvent.backend.profile.oauth;

import java.time.Instant;
import java.util.List;

public record OAuthTokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    Instant expiresAt,
    List<String> scopes,
    String rawResponse) {}
