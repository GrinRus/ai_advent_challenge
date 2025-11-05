package com.aiadvent.backend.telegram.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.telegram")
public class TelegramBotProperties {

  private boolean enabled;

  @NotNull private final Credentials bot = new Credentials();

  @NotNull private final Webhook webhook = new Webhook();

  @NotNull private final Stt stt = new Stt();

  private final List<String> allowedUpdates =
      new ArrayList<>(List.of("message", "callback_query"));

  private final List<Long> allowedUserIds = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Credentials getBot() {
    return bot;
  }

  public Webhook getWebhook() {
    return webhook;
  }

  public List<String> getAllowedUpdates() {
    return allowedUpdates;
  }

  public List<Long> getAllowedUserIds() {
    return allowedUserIds;
  }

  public Stt getStt() {
    return stt;
  }

  public static class Credentials {

    @NotBlank private String token;

    @NotBlank private String username;

    public String getToken() {
      return token;
    }

    public void setToken(String token) {
      this.token = token;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }
  }

  public static class Webhook {

    private String externalUrl;

    private String path = "/telegram/update";

    private String secretToken;

    private Duration connectionTimeout = Duration.ofSeconds(10);

    public String getExternalUrl() {
      return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
      this.externalUrl = externalUrl;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getSecretToken() {
      return secretToken;
    }

    public void setSecretToken(String secretToken) {
      this.secretToken = secretToken;
    }

    public Duration getConnectionTimeout() {
      return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
      this.connectionTimeout = connectionTimeout;
    }
  }

  public static class Stt {

    private boolean enabled = true;

    private String model = "gpt-4o-mini-transcribe";

    private String fallbackModel;

    private String language = "ru";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getFallbackModel() {
      return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
      this.fallbackModel = fallbackModel;
    }

    public String getLanguage() {
      return language;
    }

    public void setLanguage(String language) {
      this.language = language;
    }
  }
}
