package com.aiadvent.backend.chat.service;

import java.util.List;
import java.util.UUID;

public record ConversationContext(
    UUID sessionId, boolean newSession, List<ConversationMessage> history) {}
