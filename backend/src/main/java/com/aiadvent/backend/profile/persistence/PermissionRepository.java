package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.Permission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
  Optional<Permission> findByCode(String code);
}
