package com.aiadvent.backend.profile.service;

import com.aiadvent.backend.profile.domain.ProfileRole;
import com.aiadvent.backend.profile.domain.ProfileRoleId;
import com.aiadvent.backend.profile.domain.Role;
import com.aiadvent.backend.profile.domain.UserProfile;
import com.aiadvent.backend.profile.persistence.ProfileRoleRepository;
import com.aiadvent.backend.profile.persistence.RoleRepository;
import com.aiadvent.backend.profile.persistence.UserProfileRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RoleAssignmentService {

  private static final Logger log = LoggerFactory.getLogger(RoleAssignmentService.class);

  private final RoleRepository roleRepository;
  private final UserProfileRepository userProfileRepository;
  private final ProfileRoleRepository profileRoleRepository;
  private final ProfileEventLogger profileEventLogger;

  public RoleAssignmentService(
      RoleRepository roleRepository,
      UserProfileRepository userProfileRepository,
      ProfileRoleRepository profileRoleRepository,
      ProfileEventLogger profileEventLogger) {
    this.roleRepository = roleRepository;
    this.userProfileRepository = userProfileRepository;
    this.profileRoleRepository = profileRoleRepository;
    this.profileEventLogger = profileEventLogger;
  }

  public List<Role> listAvailableRoles() {
    return roleRepository.findAll();
  }

  public List<Role> listRolesForProfile(UUID profileId) {
    return profileRoleRepository.findWithRoleByProfileId(profileId).stream()
        .map(ProfileRole::getRole)
        .collect(Collectors.toList());
  }

  @Transactional
  public void assignRole(UUID profileId, String roleCode) {
    UserProfile profile =
        userProfileRepository
            .findById(profileId)
            .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + profileId));

    Role role =
        roleRepository
            .findByCode(normalize(roleCode))
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));

    ProfileRoleId id = new ProfileRoleId(profile.getId(), role.getId());
    if (profileRoleRepository.existsById(id)) {
      return;
    }
    profileRoleRepository.save(new ProfileRole(profile, role));
    log.info("role_assigned profileId={} roleCode={}", profileId, role.getCode());
    profileEventLogger.roleAssigned(profile, role);
  }

  @Transactional
  public void revokeRole(UUID profileId, String roleCode) {
    Role role =
        roleRepository
            .findByCode(normalize(roleCode))
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));
    ProfileRoleId id = new ProfileRoleId(profileId, role.getId());
    profileRoleRepository.deleteById(id);
    log.info("role_revoked profileId={} roleCode={}", profileId, role.getCode());
    userProfileRepository
        .findById(profileId)
        .ifPresent(profile -> profileEventLogger.roleRevoked(profile, role));
  }

  private String normalize(String code) {
    if (!StringUtils.hasText(code)) {
      throw new IllegalArgumentException("Role code must not be empty");
    }
    return code.trim().toLowerCase();
  }
}
