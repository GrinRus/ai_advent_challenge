package com.aiadvent.backend.profile.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "profile_role")
public class ProfileRole {

  @EmbeddedId private ProfileRoleId id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("profileId")
  @JoinColumn(name = "profile_id", nullable = false)
  private UserProfile profile;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("roleId")
  @JoinColumn(name = "role_id", nullable = false)
  private Role role;

  @jakarta.persistence.Column(name = "granted_at", nullable = false)
  private Instant grantedAt;

  protected ProfileRole() {}

  public ProfileRole(UserProfile profile, Role role) {
    this.id = new ProfileRoleId(profile.getId(), role.getId());
    this.profile = profile;
    this.role = role;
    this.grantedAt = Instant.now();
  }

  public ProfileRoleId getId() {
    return id;
  }

  public UserProfile getProfile() {
    return profile;
  }

  public Role getRole() {
    return role;
  }

  public Instant getGrantedAt() {
    return grantedAt;
  }
}
