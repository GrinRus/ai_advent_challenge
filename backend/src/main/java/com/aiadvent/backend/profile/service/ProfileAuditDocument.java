package com.aiadvent.backend.profile.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ProfileAuditDocument(
    UUID id, String eventType, String source, String channel, JsonNode metadata, Instant createdAt) {}
