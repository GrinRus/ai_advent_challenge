package com.aiadvent.backend.profile.persistence;

import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.domain.UserProfileChannel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileChannelRepository extends JpaRepository<UserProfileChannel, UUID> {
  Optional<UserProfileChannel> findByProfileAndChannel(UserProfile profile, String channel);

  java.util.List<UserProfileChannel> findByProfile(UserProfile profile);
}
