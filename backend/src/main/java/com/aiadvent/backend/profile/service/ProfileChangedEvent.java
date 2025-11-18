package com.aiadvent.backend.profile.service;

import java.time.Instant;
import java.util.UUID;

public record ProfileChangedEvent(UUID profileId, Instant updatedAt) {}
