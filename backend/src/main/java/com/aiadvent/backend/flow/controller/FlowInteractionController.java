package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowInteractionAutoResolveRequest;
import com.aiadvent.backend.flow.api.FlowInteractionItemResponse;
import com.aiadvent.backend.flow.api.FlowInteractionListResponse;
import com.aiadvent.backend.flow.api.FlowInteractionRespondRequest;
import com.aiadvent.backend.flow.api.FlowInteractionResponseSummary;
import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionResponse;
import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.service.FlowInteractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flows/{sessionId}/interactions")
public class FlowInteractionController {

  private final FlowInteractionService flowInteractionService;
  private final ObjectMapper objectMapper;

  public FlowInteractionController(
      FlowInteractionService flowInteractionService, ObjectMapper objectMapper) {
    this.flowInteractionService = flowInteractionService;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public FlowInteractionListResponse list(@PathVariable UUID sessionId) {
    List<FlowInteractionItemResponse> items =
        flowInteractionService.findRequests(sessionId).stream()
            .map(this::toItemResponse)
            .collect(Collectors.toList());

    List<FlowInteractionItemResponse> active =
        items.stream()
            .filter(item -> item.status() == FlowInteractionStatus.PENDING)
            .collect(Collectors.toList());
    List<FlowInteractionItemResponse> history =
        items.stream()
            .filter(item -> item.status() != FlowInteractionStatus.PENDING)
            .collect(Collectors.toList());

    return new FlowInteractionListResponse(active, history);
  }

  @PostMapping("/{requestId}/respond")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public FlowInteractionItemResponse respond(
      @PathVariable UUID sessionId,
      @PathVariable UUID requestId,
      @RequestHeader("X-Chat-Session-Id") UUID chatSessionIdHeader,
      @RequestBody FlowInteractionRespondRequest request) {

    if (request == null || request.chatSessionId() == null) {
      throw new IllegalArgumentException("chatSessionId is required");
    }

    FlowInteractionRequest interaction =
        requireChatSession(sessionId, requestId, chatSessionIdHeader);

    if (!interaction.getChatSessionId().equals(request.chatSessionId())) {
      throw new IllegalArgumentException("chatSessionId in body must match header value");
    }

    FlowInteractionResponseSource source =
        request.source() != null ? request.source() : FlowInteractionResponseSource.USER;

    flowInteractionService.respond(
        sessionId,
        requestId,
        chatSessionIdHeader,
        request.respondedBy(),
        sanitizePayload(request.payload()),
        source,
        FlowInteractionStatus.ANSWERED);

    FlowInteractionRequest updated = flowInteractionService.getRequest(sessionId, requestId);
    return toItemResponse(updated);
  }

  @PostMapping("/{requestId}/skip")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public FlowInteractionItemResponse skip(
      @PathVariable UUID sessionId,
      @PathVariable UUID requestId,
      @RequestHeader("X-Chat-Session-Id") UUID chatSessionIdHeader,
      @RequestBody(required = false) FlowInteractionRespondRequest request) {

    requireChatSession(sessionId, requestId, chatSessionIdHeader);

    JsonNode payload = request != null ? sanitizePayload(request.payload()) : null;

    FlowInteractionResponseSource source =
        request != null && request.source() != null
            ? request.source()
            : FlowInteractionResponseSource.USER;

    flowInteractionService.respond(
        sessionId,
        requestId,
        chatSessionIdHeader,
        request != null ? request.respondedBy() : null,
        payload,
        source,
        FlowInteractionStatus.ANSWERED);

    FlowInteractionRequest updated = flowInteractionService.getRequest(sessionId, requestId);
    return toItemResponse(updated);
  }

  @PostMapping("/{requestId}/auto")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public FlowInteractionItemResponse autoResolve(
      @PathVariable UUID sessionId,
      @PathVariable UUID requestId,
      @RequestHeader("X-Chat-Session-Id") UUID chatSessionIdHeader,
      @RequestBody(required = false) FlowInteractionAutoResolveRequest request) {

    JsonNode payload = request != null ? sanitizePayload(request.payload()) : null;

    FlowInteractionResponseSource source =
        request != null && request.source() != null
            ? request.source()
            : FlowInteractionResponseSource.SYSTEM;

    requireChatSession(sessionId, requestId, chatSessionIdHeader);

    flowInteractionService.autoResolve(
        sessionId,
        requestId,
        payload,
        source,
        request != null ? request.respondedBy() : null);

    return toItemResponse(flowInteractionService.getRequest(sessionId, requestId));
  }

  @PostMapping("/{requestId}/expire")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public FlowInteractionItemResponse expire(
      @PathVariable UUID sessionId,
      @PathVariable UUID requestId,
      @RequestHeader("X-Chat-Session-Id") UUID chatSessionIdHeader,
      @RequestBody(required = false) FlowInteractionAutoResolveRequest request) {

    requireChatSession(sessionId, requestId, chatSessionIdHeader);

    FlowInteractionResponseSource source =
        request != null && request.source() != null
            ? request.source()
            : FlowInteractionResponseSource.SYSTEM;

    flowInteractionService.expire(
        sessionId, requestId, source, request != null ? request.respondedBy() : null);

    FlowInteractionRequest updated = flowInteractionService.getRequest(sessionId, requestId);
    return toItemResponse(updated);
  }

  private FlowInteractionRequest requireChatSession(
      UUID sessionId, UUID requestId, UUID chatSessionIdHeader) {
    if (chatSessionIdHeader == null) {
      throw new IllegalArgumentException("X-Chat-Session-Id header is required");
    }
    FlowInteractionRequest interaction = flowInteractionService.getRequest(sessionId, requestId);
    if (!interaction.getChatSessionId().equals(chatSessionIdHeader)) {
      throw new IllegalArgumentException(
          "Provided chat session does not match interaction channel");
    }
    return interaction;
  }

  private FlowInteractionItemResponse toItemResponse(FlowInteractionRequest request) {
    FlowInteractionResponseSummary responseSummary =
        flowInteractionService
            .findLatestResponse(request)
            .map(response -> toSummary(response, request.getStatus()))
            .orElse(null);

    return new FlowInteractionItemResponse(
        request.getId(),
        request.getChatSessionId(),
        request.getFlowStepExecution().getStepId(),
        request.getStatus(),
        request.getType(),
        request.getTitle(),
        request.getDescription(),
        cloneNode(request.getPayloadSchema()),
        cloneNode(request.getSuggestedActions()),
        request.getCreatedAt(),
        request.getUpdatedAt(),
        request.getDueAt(),
        responseSummary);
  }

  private FlowInteractionResponseSummary toSummary(
      FlowInteractionResponse response, FlowInteractionStatus status) {
    return new FlowInteractionResponseSummary(
        response.getId(),
        response.getSource(),
        response.getRespondedBy(),
        response.getCreatedAt(),
        status,
        cloneNode(response.getPayload()));
  }

  private JsonNode cloneNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.deepCopy();
  }

  private JsonNode sanitizePayload(JsonNode payload) {
    if (payload == null || payload.isNull()) {
      return null;
    }
    return payload;
  }
}
