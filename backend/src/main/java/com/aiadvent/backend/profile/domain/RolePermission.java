package com.aiadvent.backend.profile.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "role_permission")
public class RolePermission {

  @EmbeddedId private RolePermissionId id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("roleId")
  @JoinColumn(name = "role_id", nullable = false)
  private Role role;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("permissionId")
  @JoinColumn(name = "permission_id", nullable = false)
  private Permission permission;

  protected RolePermission() {}

  public RolePermission(Role role, Permission permission) {
    this.id = new RolePermissionId(role.getId(), permission.getId());
    this.role = role;
    this.permission = permission;
  }

  public RolePermissionId getId() {
    return id;
  }

  public Role getRole() {
    return role;
  }

  public Permission getPermission() {
    return permission;
  }
}
