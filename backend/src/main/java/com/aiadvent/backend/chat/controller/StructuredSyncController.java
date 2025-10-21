package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.service.StructuredSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm/chat")
@Validated
@Tag(name = "Structured Chat", description = "Endpoints for synchronous structured responses.")
public class StructuredSyncController {

  private static final String SESSION_ID_HEADER = "X-Session-Id";
  private static final String NEW_SESSION_HEADER = "X-New-Session";

  private final StructuredSyncService structuredSyncService;

  public StructuredSyncController(StructuredSyncService structuredSyncService) {
    this.structuredSyncService = structuredSyncService;
  }

  @PostMapping(
      value = "/sync/structured",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Request a structured synchronous response from the selected provider.",
      description =
          "Creates or reuses a chat session, enforces JSON schema via Spring AI BeanOutputConverter, and returns the structured payload.")
  @ApiResponse(
      responseCode = "200",
      description = "Structured response from the provider.",
      headers = {
        @Header(
            name = SESSION_ID_HEADER,
            description = "Identifier of the chat session associated with the response."),
        @Header(name = NEW_SESSION_HEADER, description = "Indicates whether the session is newly created.")
      },
      content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StructuredSyncResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description = "Provider response failed JSON schema validation.",
      content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  @ApiResponse(
      responseCode = "429",
      description = "Provider rate-limited the request.",
      content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  @ApiResponse(
      responseCode = "502",
      description = "Upstream provider returned an error.",
      content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  public ResponseEntity<StructuredSyncResponse> sync(@Valid @RequestBody ChatSyncRequest request) {
    StructuredSyncService.StructuredSyncResult result = structuredSyncService.sync(request);

    HttpHeaders headers = new HttpHeaders();
    headers.add(SESSION_ID_HEADER, result.context().sessionId().toString());
    headers.add(NEW_SESSION_HEADER, Boolean.toString(result.context().newSession()));

    return ResponseEntity.ok().headers(headers).body(result.response());
  }
}
