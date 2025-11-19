package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.OAuthToken;
import com.aiadvent.backend.profile.domain.UserIdentity;
import com.aiadvent.backend.profile.oauth.OAuthTokenResponse;
import com.aiadvent.backend.profile.persistence.OAuthTokenRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthTokenService {

  private final OAuthTokenRepository repository;

  public OAuthTokenService(OAuthTokenRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void storeToken(UserIdentity identity, OAuthTokenResponse response) {
    if (identity == null || response == null) {
      return;
    }
    OAuthToken token =
        repository
            .findByIdentity(identity)
            .orElseGet(
                () -> {
                  OAuthToken created = new OAuthToken();
                  created.setIdentity(identity);
                  return created;
                });
    token.setProvider(identity.getProvider());
    token.setAccessToken(response.accessToken());
    token.setRefreshToken(response.refreshToken());
    token.setTokenType(response.tokenType());
    token.setScopes(response.scopes() != null ? response.scopes() : List.of());
    token.setExpiresAt(response.expiresAt());
    repository.save(token);
  }

  public Optional<OAuthToken> findByIdentity(UserIdentity identity) {
    return repository.findByIdentity(identity);
  }
}
