package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.api.ProfileAdminSummary;
import com.aiadvent.backend.profile.domain.ProfileRole;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.persistence.ProfileRoleRepository;
import com.aiadvent.backend.profile.persistence.UserProfileRepository;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProfileAdminQueryService {

  private final UserProfileRepository userProfileRepository;
  private final ProfileRoleRepository profileRoleRepository;

  public ProfileAdminQueryService(
      UserProfileRepository userProfileRepository, ProfileRoleRepository profileRoleRepository) {
    this.userProfileRepository = userProfileRepository;
    this.profileRoleRepository = profileRoleRepository;
  }

  @Transactional(readOnly = true)
  public Page<ProfileAdminSummary> listProfiles(
      @Nullable String namespaceFilter, @Nullable String referenceFilter, int page, int size) {
    Pageable pageable =
        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("updatedAt").descending());
    Specification<UserProfile> spec = buildSpecification(namespaceFilter, referenceFilter);
    Page<UserProfile> result = userProfileRepository.findAll(spec, pageable);
    Map<UUID, List<String>> roles = loadRoles(result.getContent());
    return result.map(
        profile ->
            new ProfileAdminSummary(
                profile.getId(),
                profile.getNamespace(),
                profile.getReference(),
                profile.getDisplayName(),
                profile.getLocale(),
                profile.getTimezone(),
                roles.getOrDefault(profile.getId(), List.of()),
                profile.getUpdatedAt()));
  }

  private Specification<UserProfile> buildSpecification(String namespace, String reference) {
    Specification<UserProfile> spec = Specification.where(null);
    if (StringUtils.hasText(namespace)) {
      String normalized = namespace.trim().toLowerCase(Locale.ROOT);
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(cb.lower(root.get("namespace")), "%" + normalized + "%"));
    }
    if (StringUtils.hasText(reference)) {
      String normalized = reference.trim().toLowerCase(Locale.ROOT);
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(cb.lower(root.get("reference")), "%" + normalized + "%"));
    }
    return spec;
  }

  private Map<UUID, List<String>> loadRoles(Collection<UserProfile> profiles) {
    if (profiles.isEmpty()) {
      return Map.of();
    }
    List<UUID> profileIds =
        profiles.stream().map(UserProfile::getId).collect(Collectors.toUnmodifiableList());
    return profileRoleRepository.findByIdProfileIdIn(profileIds).stream()
        .filter(role -> role.getRole() != null && StringUtils.hasText(role.getRole().getCode()))
        .collect(
            Collectors.groupingBy(
                entry -> entry.getId().getProfileId(),
                Collectors.mapping(
                    entry -> entry.getRole().getCode().toLowerCase(Locale.ROOT), Collectors.toList())));
  }
}
