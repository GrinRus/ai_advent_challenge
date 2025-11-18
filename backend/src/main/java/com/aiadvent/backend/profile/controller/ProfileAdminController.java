package com.aiadvent.backend.profile.controller;

import com.aiadvent.backend.profile.api.ProfileAdminSummary;
import com.aiadvent.backend.profile.domain.Role;
import com.aiadvent.backend.profile.service.ProfileAdminQueryService;
import com.aiadvent.backend.profile.service.RoleAssignmentService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/roles")
public class ProfileAdminController {

  private final RoleAssignmentService roleAssignmentService;
  private final ProfileAdminQueryService profileAdminQueryService;

  public ProfileAdminController(
      RoleAssignmentService roleAssignmentService,
      ProfileAdminQueryService profileAdminQueryService) {
    this.roleAssignmentService = roleAssignmentService;
    this.profileAdminQueryService = profileAdminQueryService;
  }

  @GetMapping
  public List<Role> listRoles() {
    return roleAssignmentService.listAvailableRoles();
  }

  @GetMapping("/profiles")
  public Page<ProfileAdminSummary> listProfiles(
      @RequestParam(value = "namespace", required = false) String namespace,
      @RequestParam(value = "reference", required = false) String reference,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "20") int size) {
    return profileAdminQueryService.listProfiles(namespace, reference, page, size);
  }

  @GetMapping("/{profileId}")
  public List<Role> listRolesForProfile(@PathVariable UUID profileId) {
    return roleAssignmentService.listRolesForProfile(profileId);
  }

  @PostMapping("/{profileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void assignRole(@PathVariable UUID profileId, @RequestBody RoleRequest request) {
    roleAssignmentService.assignRole(profileId, request.roleCode());
  }

  @DeleteMapping("/{profileId}/{roleCode}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeRole(@PathVariable UUID profileId, @PathVariable String roleCode) {
    roleAssignmentService.revokeRole(profileId, roleCode);
  }

  public record RoleRequest(String roleCode) {}
}
