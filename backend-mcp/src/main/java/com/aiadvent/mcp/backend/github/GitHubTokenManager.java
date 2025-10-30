package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubTokenManager {

  private static final Logger log = LoggerFactory.getLogger(GitHubTokenManager.class);

  private final GitHubBackendProperties properties;
  private final GitHubClientFactory clientFactory;
  private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
  private final Object tokenLock = new Object();
  private final AtomicReference<PrivateKey> cachedPrivateKey = new AtomicReference<>();

  GitHubTokenManager(GitHubBackendProperties properties, GitHubClientFactory clientFactory) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  String currentToken() {
    String pat = properties.getPersonalAccessToken();
    if (StringUtils.hasText(pat)) {
      return pat.trim();
    }
    ensureAppCredentials();

    CachedToken snapshot = cachedToken.get();
    if (snapshot != null && !snapshot.isExpired(properties.getTokenRefreshSkew())) {
      return snapshot.value();
    }
    synchronized (tokenLock) {
      snapshot = cachedToken.get();
      if (snapshot != null && !snapshot.isExpired(properties.getTokenRefreshSkew())) {
        return snapshot.value();
      }
      CachedToken refreshed = refreshInstallationToken();
      cachedToken.set(refreshed);
      return refreshed.value();
    }
  }

  private CachedToken refreshInstallationToken() {
    String jwt = generateAppJwt();
    try {
      GitHub appClient = clientFactory.createAppClient(jwt);
      GHApp app = appClient.getApp();
      long installationId = requireInstallationId();
      GHAppInstallation installation = app.getInstallationById(installationId);
      if (installation == null) {
        throw new IllegalStateException(
            "GitHub App installation not found for id %s".formatted(installationId));
      }
      GHAppInstallationToken token = installation.createToken().create();
      Instant expiresAt =
          token.getExpiresAt() != null ? token.getExpiresAt().toInstant() : Instant.now().plusSeconds(3600);
      log.debug(
          "Generated GitHub installation token; expires at {} (installationId={})",
          expiresAt,
          installationId);
      return new CachedToken(token.getToken(), expiresAt);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to refresh GitHub installation token", ex);
    }
  }

  private String generateAppJwt() {
    String appId = properties.getAppId();
    if (!StringUtils.hasText(appId)) {
      throw new IllegalStateException("GitHub App ID must be provided when using app authentication");
    }
    PrivateKey privateKey = resolvePrivateKey();
    Instant now = Instant.now();
    Instant issuedAt = now.minusSeconds(30);
    Duration ttl = properties.getAppJwtTtl() != null ? properties.getAppJwtTtl() : Duration.ofMinutes(8);
    if (ttl.compareTo(Duration.ofMinutes(10)) > 0) {
      ttl = Duration.ofMinutes(10);
    }
    Instant expiresAt = issuedAt.plus(ttl);

    Algorithm algorithm =
        Algorithm.RSA256(
            null,
            privateKey instanceof RSAPrivateKey rsaPrivateKey
                ? rsaPrivateKey
                : castToRsa(privateKey));

    return JWT.create()
        .withIssuer(appId.trim())
        .withIssuedAt(Date.from(issuedAt))
        .withExpiresAt(Date.from(expiresAt))
        .sign(algorithm);
  }

  private RSAPrivateKey castToRsa(PrivateKey privateKey) {
    if (privateKey instanceof RSAPrivateKey rsa) {
      return rsa;
    }
    throw new IllegalStateException("GitHub App private key must be an RSA private key");
  }

  private PrivateKey resolvePrivateKey() {
    PrivateKey cached = cachedPrivateKey.get();
    if (cached != null) {
      return cached;
    }
    synchronized (cachedPrivateKey) {
      cached = cachedPrivateKey.get();
      if (cached != null) {
        return cached;
      }
      PrivateKey parsed = parsePrivateKey(properties.getPrivateKeyBase64());
      cachedPrivateKey.set(parsed);
      return parsed;
    }
  }

  private PrivateKey parsePrivateKey(String base64Input) {
    if (!StringUtils.hasText(base64Input)) {
      throw new IllegalStateException("GitHub App private key (Base64) must be provided");
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(base64Input.trim());
      String pem = new String(decoded, StandardCharsets.UTF_8);
      String sanitized =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      byte[] keyBytes = Base64.getDecoder().decode(sanitized);
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePrivate(spec);
    } catch (IllegalArgumentException | GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to parse GitHub App private key", ex);
    }
  }

  private long requireInstallationId() {
    Long installationId = properties.getInstallationId();
    if (installationId == null || installationId <= 0) {
      throw new IllegalStateException("GitHub App installation ID must be provided");
    }
    return installationId;
  }

  private void ensureAppCredentials() {
    if (!StringUtils.hasText(properties.getAppId())
        || !StringUtils.hasText(properties.getPrivateKeyBase64())
        || properties.getInstallationId() == null) {
      throw new IllegalStateException(
          "GitHub App credentials are not fully configured. Provide appId, installationId and private key.");
    }
  }

  private record CachedToken(String value, Instant expiresAt) {

    boolean isExpired(Duration skew) {
      Duration refreshSkew = skew != null ? skew : Duration.ofMinutes(1);
      Instant refreshAt = expiresAt.minus(refreshSkew);
      return Instant.now().isAfter(refreshAt);
    }
  }
}
