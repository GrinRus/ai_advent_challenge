package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.OAuthToken;
import com.aiadvent.backend.profile.domain.UserIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {
  Optional<OAuthToken> findByIdentity(UserIdentity identity);
}
