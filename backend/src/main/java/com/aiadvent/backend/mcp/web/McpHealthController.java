package com.aiadvent.backend.mcp.web;

import com.aiadvent.backend.mcp.service.McpCatalogService;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServer;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpTool;
import com.aiadvent.backend.mcp.web.dto.McpHealthEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/mcp")
public class McpHealthController {

  private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);

  private final McpCatalogService catalogService;

  public McpHealthController(McpCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<McpHealthEvent>> streamHealth(
      @RequestParam(name = "interval", required = false) Duration interval) {
    Duration pollInterval = sanitize(interval);
    return Flux.interval(Duration.ZERO, pollInterval)
        .flatMap(ignore -> Flux.fromIterable(toEvents()))
        .onBackpressureDrop();
  }

  private Duration sanitize(Duration interval) {
    if (interval == null || interval.isNegative() || interval.isZero()) {
      return DEFAULT_INTERVAL;
    }
    return interval;
  }

  private List<ServerSentEvent<McpHealthEvent>> toEvents() {
    McpCatalogResponse catalog = catalogService.getCatalog();
    Instant now = Instant.now();
    return catalog.servers().stream()
        .map(server -> ServerSentEvent.<McpHealthEvent>builder()
            .id(server.id())
            .event("mcp-server")
            .data(toEvent(server, now))
            .build())
        .collect(Collectors.toList());
  }

  private McpHealthEvent toEvent(McpServer server, Instant timestamp) {
    List<McpTool> tools = server.tools();
    List<String> available =
        tools.stream().filter(McpTool::available).map(McpTool::code).toList();
    List<String> unavailable =
        tools.stream().filter(tool -> !tool.available()).map(McpTool::code).toList();
    return new McpHealthEvent(
        server.id(), server.status(), tools.size(), available, unavailable, timestamp);
  }
}

