package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.service.FlowStatusService;
import com.aiadvent.backend.flow.service.FlowStatusService.FlowStatusResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/flows")
public class FlowEventStreamController {

  private static final Logger log = LoggerFactory.getLogger(FlowEventStreamController.class);
  private static final Duration STREAM_POLL_TIMEOUT = Duration.ofSeconds(5);

  private final FlowStatusService flowStatusService;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public FlowEventStreamController(FlowStatusService flowStatusService) {
    this.flowStatusService = flowStatusService;
  }

  @GetMapping(value = "/{sessionId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID sessionId) {
    SseEmitter emitter = new SseEmitter(0L);
    CompletableFuture.runAsync(() -> streamLoop(sessionId, emitter), executor)
        .exceptionally(
            throwable -> {
              emitter.completeWithError(throwable);
              return null;
            });
    return emitter;
  }

  private void streamLoop(UUID sessionId, SseEmitter emitter) {
    Long sinceEventId = null;
    Long stateVersion = null;
    boolean active = true;

    try {
      while (active) {
        Optional<FlowStatusResponse> response =
            flowStatusService.pollSession(sessionId, sinceEventId, stateVersion, STREAM_POLL_TIMEOUT);

        if (response.isPresent()) {
          FlowStatusResponse payload = response.get();
          emitter.send(SseEmitter.event().name("flow").data(payload));
          sinceEventId = payload.nextSinceEventId();
          stateVersion = payload.state().stateVersion();

          if (payload.state().status().name().endsWith("ED")) {
            active = false;
          }
        } else {
          emitter.send(SseEmitter.event().name("heartbeat").data("keep-alive"));
        }
      }
      emitter.complete();
    } catch (IOException exception) {
      log.debug("SSE stream closed for session {}", sessionId, exception);
      emitter.completeWithError(exception);
    }
  }
}
