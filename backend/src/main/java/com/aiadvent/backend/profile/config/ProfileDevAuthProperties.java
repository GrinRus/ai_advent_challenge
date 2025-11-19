package com.aiadvent.backend.profile.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.profile.dev")
public class ProfileDevAuthProperties {

  private boolean enabled = false;
  private String token;
  private Duration linkTtl = Duration.ofMinutes(10);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public Duration getLinkTtl() {
    return linkTtl;
  }

  public void setLinkTtl(Duration linkTtl) {
    this.linkTtl = linkTtl;
  }
}
