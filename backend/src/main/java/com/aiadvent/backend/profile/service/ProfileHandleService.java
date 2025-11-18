package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.ProfileLookup;
import com.aiadvent.backend.profile.domain.ProfileLookupId;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.persistence.ProfileLookupRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProfileHandleService {

  private static final String INSERT_SQL =
      """
      INSERT INTO profile_lookup (namespace, reference, profile_id, created_at)
      VALUES (:namespace, :reference, :profileId, now())
      ON CONFLICT (namespace, reference) DO NOTHING
      """;

  private final ProfileLookupRepository profileLookupRepository;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  public ProfileHandleService(
      ProfileLookupRepository profileLookupRepository,
      NamedParameterJdbcTemplate jdbcTemplate) {
    this.profileLookupRepository = profileLookupRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional(readOnly = true)
  public Optional<UUID> findProfileId(String namespace, String reference) {
    ProfileLookupId id = new ProfileLookupId(normalize(namespace), normalize(reference));
    return profileLookupRepository.findById(id).map(ProfileLookup::getProfileId);
  }

  @Transactional
  public UUID resolveOrCreateHandle(
      String namespace, String reference, Supplier<UserProfile> profileSupplier) {
    ProfileLookupId id = new ProfileLookupId(normalize(namespace), normalize(reference));

    return profileLookupRepository
        .findById(id)
        .map(ProfileLookup::getProfileId)
        .orElseGet(() -> registerHandle(id, profileSupplier));
  }

  private UUID registerHandle(ProfileLookupId id, Supplier<UserProfile> profileSupplier) {
    UserProfile profile = profileSupplier.get();
    if (profile == null || profile.getId() == null) {
      throw new IllegalArgumentException("Profile supplier must provide a persisted profile");
    }

    Map<String, Object> params =
        Map.of(
            "namespace", id.getNamespace(),
            "reference", id.getReference(),
            "profileId", profile.getId());
    jdbcTemplate.update(INSERT_SQL, params);

    return profileLookupRepository
        .findById(id)
        .map(ProfileLookup::getProfileId)
        .orElseThrow(() -> new IllegalStateException("Failed to register profile handle"));
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("Handle components must not be blank");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
