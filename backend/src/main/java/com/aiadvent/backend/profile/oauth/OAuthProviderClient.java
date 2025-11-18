package com.aiadvent.backend.profile.oauth;

import java.net.URI;
import java.util.Optional;

public interface OAuthProviderClient {

  String providerId();

  URI buildAuthorizationUri(OAuthAuthorizationRequest request);

  OAuthTokenResponse exchangeCode(OAuthAuthorizationExchange exchange);

  OAuthTokenResponse refreshToken(OAuthTokenRefresh refresh);

  Optional<OAuthUserInfo> fetchUserInfo(String accessToken);

  record OAuthAuthorizationExchange(
      String redirectUri, String code, String codeVerifier, String deviceId) {}

  record OAuthTokenRefresh(String refreshToken) {}
}
