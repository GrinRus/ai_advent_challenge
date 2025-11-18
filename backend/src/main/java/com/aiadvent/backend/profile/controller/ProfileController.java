package com.aiadvent.backend.profile.controller;

import com.aiadvent.backend.profile.api.IdentityRequest;
import com.aiadvent.backend.profile.api.ProfileUpdateRequest;
import com.aiadvent.backend.profile.service.ProfileLookupKey;
import com.aiadvent.backend.profile.service.UserProfileDocument;
import com.aiadvent.backend.profile.service.UserProfileService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

  private static final String PROFILE_KEY_HEADER = "X-Profile-Key";
  private static final String PROFILE_CHANNEL_HEADER = "X-Profile-Channel";

  private final UserProfileService userProfileService;

  public ProfileController(UserProfileService userProfileService) {
    this.userProfileService = userProfileService;
  }

  @GetMapping("/{namespace}/{reference}")
  public UserProfileDocument getProfile(
      @PathVariable String namespace,
      @PathVariable String reference,
      @RequestHeader(PROFILE_KEY_HEADER) String profileKeyHeader,
      @RequestHeader(value = PROFILE_CHANNEL_HEADER, required = false) String channelHeader,
      HttpServletResponse response) {
    ProfileLookupKey key = parseAndValidate(namespace, reference, profileKeyHeader, channelHeader);
    UserProfileDocument document = userProfileService.resolveProfile(key);
    setEtag(response, document);
    return document;
  }

  @PutMapping("/{namespace}/{reference}")
  public UserProfileDocument updateProfile(
      @PathVariable String namespace,
      @PathVariable String reference,
      @RequestHeader(PROFILE_KEY_HEADER) String profileKeyHeader,
      @RequestHeader(value = PROFILE_CHANNEL_HEADER, required = false) String channelHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody ProfileUpdateRequest request,
      HttpServletResponse response) {
    ProfileLookupKey key = parseAndValidate(namespace, reference, profileKeyHeader, channelHeader);
    UserProfileDocument document =
        userProfileService.updateProfile(key, request.toCommand(), ifMatch);
    setEtag(response, document);
    return document;
  }

  @PostMapping("/{namespace}/{reference}/identities")
  public UserProfileDocument attachIdentity(
      @PathVariable String namespace,
      @PathVariable String reference,
      @RequestHeader(PROFILE_KEY_HEADER) String profileKeyHeader,
      @RequestHeader(value = PROFILE_CHANNEL_HEADER, required = false) String channelHeader,
      @Valid @RequestBody IdentityRequest request,
      HttpServletResponse response) {
    ProfileLookupKey key = parseAndValidate(namespace, reference, profileKeyHeader, channelHeader);
    UserProfileDocument document = userProfileService.attachIdentity(key, request.toCommand());
    setEtag(response, document);
    return document;
  }

  @DeleteMapping("/{namespace}/{reference}/identities/{provider}/{externalId}")
  public UserProfileDocument detachIdentity(
      @PathVariable String namespace,
      @PathVariable String reference,
      @PathVariable String provider,
      @PathVariable String externalId,
      @RequestHeader(PROFILE_KEY_HEADER) String profileKeyHeader,
      @RequestHeader(value = PROFILE_CHANNEL_HEADER, required = false) String channelHeader,
      HttpServletResponse response) {
    ProfileLookupKey key = parseAndValidate(namespace, reference, profileKeyHeader, channelHeader);
    UserProfileDocument document = userProfileService.detachIdentity(key, provider, externalId);
    setEtag(response, document);
    return document;
  }

  private ProfileLookupKey parseAndValidate(
      String pathNamespace, String pathReference, String header, String channelHeader) {
    if (!StringUtils.hasText(header)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, PROFILE_KEY_HEADER + " header is required");
    }
    String[] parts = header.split(":", 2);
    if (parts.length != 2) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          PROFILE_KEY_HEADER + " must be in format namespace:reference");
    }

    String normalizedNamespace = normalize(pathNamespace);
    String normalizedReference = normalize(pathReference);
    String effectiveChannel =
        StringUtils.hasText(channelHeader) ? channelHeader.trim() : null;

    ProfileLookupKey headerKey =
        new ProfileLookupKey(parts[0].trim(), parts[1].trim(), effectiveChannel);
    if (!headerKey.normalizedNamespace().equals(normalizedNamespace)
        || !headerKey.normalizedReference().equals(normalizedReference)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Profile header does not match requested resource");
    }

    String normalizedChannel = normalizeOptional(effectiveChannel);
    return new ProfileLookupKey(normalizedNamespace, normalizedReference, normalizedChannel);
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Namespace and reference must not be blank");
    }
    return value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private String normalizeOptional(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(java.util.Locale.ROOT) : null;
  }

  private void setEtag(HttpServletResponse response, UserProfileDocument document) {
    if (response == null || document == null) {
      return;
    }
    response.setHeader("ETag", "W/\"" + document.version() + "\"");
  }
}
