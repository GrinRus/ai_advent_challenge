package com.aiadvent.backend.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProfileRoleId implements Serializable {

  @Column(name = "profile_id", nullable = false)
  private UUID profileId;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  protected ProfileRoleId() {}

  public ProfileRoleId(UUID profileId, UUID roleId) {
    this.profileId = profileId;
    this.roleId = roleId;
  }

  public UUID getProfileId() {
    return profileId;
  }

  public UUID getRoleId() {
    return roleId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProfileRoleId that = (ProfileRoleId) o;
    return Objects.equals(profileId, that.profileId) && Objects.equals(roleId, that.roleId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(profileId, roleId);
  }
}
