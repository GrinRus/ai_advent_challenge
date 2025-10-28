package com.aiadvent.backend.mcp.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

public class ReactorClientHttpConnectorBuilder {

  private Duration connectTimeout = Duration.ofSeconds(10);
  private Duration readTimeout = Duration.ofSeconds(30);

  public ReactorClientHttpConnectorBuilder connectTimeout(Duration connectTimeout) {
    if (connectTimeout != null) {
      this.connectTimeout = connectTimeout;
    }
    return this;
  }

  public ReactorClientHttpConnectorBuilder readTimeout(Duration readTimeout) {
    if (readTimeout != null) {
      this.readTimeout = readTimeout;
    }
    return this;
  }

  public ClientHttpConnector build() {
    HttpClient client =
        HttpClient.create()
            .responseTimeout(readTimeout)
            .proxyWithSystemProperties()
            .compress(true)
            .keepAlive(true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());
    return new ReactorClientHttpConnector(client);
  }
}
