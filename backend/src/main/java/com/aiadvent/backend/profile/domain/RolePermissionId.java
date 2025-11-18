package com.aiadvent.backend.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class RolePermissionId implements Serializable {

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "permission_id", nullable = false)
  private UUID permissionId;

  protected RolePermissionId() {}

  public RolePermissionId(UUID roleId, UUID permissionId) {
    this.roleId = roleId;
    this.permissionId = permissionId;
  }

  public UUID getRoleId() {
    return roleId;
  }

  public UUID getPermissionId() {
    return permissionId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RolePermissionId that = (RolePermissionId) o;
    return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(roleId, permissionId);
  }
}
