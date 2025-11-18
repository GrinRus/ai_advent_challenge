package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.RolePermission;
import com.aiadvent.backend.profile.domain.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {}
