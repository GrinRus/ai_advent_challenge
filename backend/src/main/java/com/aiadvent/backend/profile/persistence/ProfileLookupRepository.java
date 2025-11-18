package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.ProfileLookup;
import com.aiadvent.backend.profile.domain.ProfileLookupId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileLookupRepository extends JpaRepository<ProfileLookup, ProfileLookupId> {
  Optional<ProfileLookup> findById(ProfileLookupId id);
}
