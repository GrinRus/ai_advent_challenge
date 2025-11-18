package com.aiadvent.backend.profile.oauth;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class OAuthProviderRegistry {

  private final Map<String, OAuthProviderClient> clients = new ConcurrentHashMap<>();

  public void register(OAuthProviderClient client) {
    clients.put(client.providerId(), client);
  }

  public Optional<OAuthProviderClient> find(String providerId) {
    return Optional.ofNullable(clients.get(providerId));
  }

  public Collection<String> providerIds() {
    return clients.keySet();
  }
}
