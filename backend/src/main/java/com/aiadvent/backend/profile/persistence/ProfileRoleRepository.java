package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.ProfileRole;
import com.aiadvent.backend.profile.domain.ProfileRoleId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRoleRepository extends JpaRepository<ProfileRole, ProfileRoleId> {
  List<ProfileRole> findByIdProfileId(UUID profileId);
}
