package com.aiadvent.backend.chat.domain;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Typed wrapper around structured assistant responses persisted for chat messages. Replaces raw
 * {@code JsonNode} usage and guarantees a consistent (possibly empty) JSON representation.
 */
public final class ChatStructuredPayload extends AbstractJsonPayload {

  private static final ChatStructuredPayload EMPTY =
      new ChatStructuredPayload(MissingNode.getInstance());

  @JsonCreator
  public ChatStructuredPayload(JsonNode value) {
    super(value);
  }

  public static ChatStructuredPayload empty() {
    return EMPTY;
  }

  public static ChatStructuredPayload from(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return empty();
    }
    return new ChatStructuredPayload(value);
  }
}
