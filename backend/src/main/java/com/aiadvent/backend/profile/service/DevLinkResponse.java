package com.aiadvent.backend.profile.service;

import java.time.Instant;
import java.util.UUID;

public record DevLinkResponse(
    String code,
    UUID profileId,
    String namespace,
    String reference,
    String channel,
    Instant expiresAt) {}
