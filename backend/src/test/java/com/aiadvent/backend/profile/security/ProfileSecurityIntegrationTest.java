package com.aiadvent.backend.profile.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.profile.service.ProfileLookupKey;
import com.aiadvent.backend.profile.service.RoleAssignmentService;
import com.aiadvent.backend.profile.service.UserProfileDocument;
import com.aiadvent.backend.profile.service.UserProfileService;
import com.aiadvent.backend.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "app.profile.dev.enabled=true",
      "app.profile.dev.token=test-dev-token",
      "app.profile.security.enabled=true"
    })
class ProfileSecurityIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserProfileService userProfileService;
  @Autowired private RoleAssignmentService roleAssignmentService;

  @Test
  void profileEndpointsRequireDevToken() throws Exception {
    mockMvc
        .perform(get("/api/profile/web/demo").header("X-Profile-Key", "web:demo"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminEndpointsRequireAdminRole() throws Exception {
    ProfileLookupKey key = new ProfileLookupKey("web", "security-admin", "web");
    UserProfileDocument profile = userProfileService.resolveProfile(key);

    mockMvc
        .perform(
            get("/api/admin/roles")
                .header("X-Profile-Key", "web:security-admin")
                .header("X-Profile-Channel", "web")
                .header("X-Profile-Auth", "test-dev-token"))
        .andExpect(status().isForbidden());

    roleAssignmentService.assignRole(profile.profileId(), "admin");
    userProfileService.evict(key);

    mockMvc
        .perform(
            get("/api/admin/roles")
                .header("X-Profile-Key", "web:security-admin")
                .header("X-Profile-Channel", "web")
                .header("X-Profile-Auth", "test-dev-token"))
        .andExpect(status().isOk());
  }
}
