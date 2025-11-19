package com.aiadvent.backend.profile.security;

import com.aiadvent.backend.profile.config.ProfileDevAuthProperties;
import com.aiadvent.backend.profile.service.ProfileLookupKey;
import com.aiadvent.backend.profile.service.UserProfileDocument;
import com.aiadvent.backend.profile.service.UserProfileService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProfileSecurityService {

  private static final Logger log = LoggerFactory.getLogger(ProfileSecurityService.class);
  private static final String PROFILE_KEY_HEADER = "X-Profile-Key";
  private static final String PROFILE_CHANNEL_HEADER = "X-Profile-Channel";
  private static final String DEV_TOKEN_HEADER = "X-Profile-Auth";

  private final ProfileDevAuthProperties devAuthProperties;
  private final UserProfileService userProfileService;

  public ProfileSecurityService(
      ProfileDevAuthProperties devAuthProperties, UserProfileService userProfileService) {
    this.devAuthProperties = devAuthProperties;
    this.userProfileService = userProfileService;
  }

  public ProfileLookupKey resolveProfileKey(HttpServletRequest request) {
    String headerValue = request.getHeader(PROFILE_KEY_HEADER);
    if (!StringUtils.hasText(headerValue)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, PROFILE_KEY_HEADER + " header is required");
    }
    String[] parts = headerValue.split(":", 2);
    if (parts.length != 2) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, PROFILE_KEY_HEADER + " must be namespace:reference");
    }
    String namespace = normalize(parts[0]);
    String reference = normalize(parts[1]);
    String channelHeader = request.getHeader(PROFILE_CHANNEL_HEADER);
    String channel = normalizeOptional(channelHeader);
    return new ProfileLookupKey(namespace, reference, channel);
  }

  public void ensureDevAccess(HttpServletRequest request) {
    if (!devAuthProperties.isEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Profile dev authentication is disabled");
    }
    String configuredToken = devAuthProperties.getToken();
    if (!StringUtils.hasText(configuredToken)) {
      log.warn("Profile dev authentication enabled but token is not configured");
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Profile dev token is not configured");
    }
    String header = request.getHeader(DEV_TOKEN_HEADER);
    if (!StringUtils.hasText(header) || !configuredToken.equals(header.trim())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing dev token");
    }
  }

  public void ensureAdminRole(ProfileLookupKey key) {
    UserProfileDocument profile = userProfileService.resolveProfile(key);
    boolean isAdmin =
        profile.roles() != null
            && profile.roles().stream().anyMatch(role -> "admin".equalsIgnoreCase(role));
    if (!isAdmin) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role is required");
    }
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Profile handle components must not be blank");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeOptional(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
  }
}
