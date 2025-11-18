package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.UserProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserProfileRepository
    extends JpaRepository<UserProfile, UUID>, JpaSpecificationExecutor<UserProfile> {
  Optional<UserProfile> findByNamespaceAndReference(String namespace, String reference);
}
