package com.aiadvent.backend.profile.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProfileAdminSummary(
    UUID profileId,
    String namespace,
    String reference,
    String displayName,
    String locale,
    String timezone,
    List<String> roles,
    Instant updatedAt) {}
