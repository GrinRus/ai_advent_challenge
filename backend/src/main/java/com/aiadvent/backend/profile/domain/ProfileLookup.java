package com.aiadvent.backend.profile.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profile_lookup")
public class ProfileLookup {

  @EmbeddedId private ProfileLookupId id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "profile_id", nullable = false)
  private UserProfile profile;

  @jakarta.persistence.Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ProfileLookup() {}

  public ProfileLookup(ProfileLookupId id, UserProfile profile) {
    this.id = id;
    this.profile = profile;
    this.createdAt = Instant.now();
  }

  public ProfileLookupId getId() {
    return id;
  }

  public UserProfile getProfile() {
    return profile;
  }

  public UUID getProfileId() {
    return profile != null ? profile.getId() : null;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
