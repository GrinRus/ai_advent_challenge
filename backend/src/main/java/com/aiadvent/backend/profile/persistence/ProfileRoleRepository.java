package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.ProfileRole;
import com.aiadvent.backend.profile.domain.ProfileRoleId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileRoleRepository extends JpaRepository<ProfileRole, ProfileRoleId> {
  List<ProfileRole> findByIdProfileId(UUID profileId);

  List<ProfileRole> findByIdProfileIdIn(Collection<UUID> profileIds);

  @Query(
      "select pr from ProfileRole pr join fetch pr.role where pr.id.profileId = :profileId")
  List<ProfileRole> findWithRoleByProfileId(@Param("profileId") UUID profileId);
}
