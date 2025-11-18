package com.aiadvent.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.profile.domain.Role;
import com.aiadvent.backend.support.PostgresTestContainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RoleAssignmentServiceIntegrationTest extends PostgresTestContainer {

  @Autowired private RoleAssignmentService roleAssignmentService;

  @Autowired private UserProfileService userProfileService;

  @Test
  void assignRoleIsIdempotent() {
    UUID profileId =
        userProfileService.resolveProfile(new ProfileLookupKey("web", "role-assign", "web"))
            .profileId();

    roleAssignmentService.assignRole(profileId, "admin");
    roleAssignmentService.assignRole(profileId, "ADMIN");

    List<Role> roles = roleAssignmentService.listRolesForProfile(profileId);
    assertThat(roles)
        .hasSize(1)
        .extracting(Role::getCode)
        .containsExactly("admin");
  }

  @Test
  void revokeRoleRemovesLink() {
    UUID profileId =
        userProfileService.resolveProfile(new ProfileLookupKey("web", "role-revoke", "web"))
            .profileId();

    roleAssignmentService.assignRole(profileId, "operator");
    assertThat(roleAssignmentService.listRolesForProfile(profileId))
        .extracting(Role::getCode)
        .containsExactly("operator");

    roleAssignmentService.revokeRole(profileId, "operator");
    assertThat(roleAssignmentService.listRolesForProfile(profileId)).isEmpty();
  }

  @Test
  void listAvailableRolesReturnsSeededRoles() {
    List<Role> roles = roleAssignmentService.listAvailableRoles();
    assertThat(roles)
        .extracting(Role::getCode)
        .contains("admin", "operator", "user");
  }
}
