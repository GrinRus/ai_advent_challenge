package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.UserIdentity;
import com.aiadvent.backend.profile.domain.UserProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {
  Optional<UserIdentity> findByProviderAndExternalId(String provider, String externalId);

  java.util.List<UserIdentity> findByProfile(UserProfile profile);

  long countByProfile(UserProfile profile);
}
