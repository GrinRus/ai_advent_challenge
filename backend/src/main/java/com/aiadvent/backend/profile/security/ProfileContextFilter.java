package com.aiadvent.backend.profile.security;

import com.aiadvent.backend.profile.service.ProfileContextHolder;
import com.aiadvent.backend.profile.service.ProfileLookupKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProfileContextFilter extends OncePerRequestFilter {

  private static final String PROFILE_KEY_HEADER = "X-Profile-Key";
  private static final String PROFILE_CHANNEL_HEADER = "X-Profile-Channel";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!requiresProfileContext(path)) {
      filterChain.doFilter(request, response);
      return;
    }

    String profileKey = request.getHeader(PROFILE_KEY_HEADER);
    if (!StringUtils.hasText(profileKey)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, PROFILE_KEY_HEADER + " header is required");
    }

    ProfileLookupKey lookupKey =
        new ProfileLookupKey(
            normalize(part(profileKey, 0)),
            normalize(part(profileKey, 1)),
            normalizeOptional(request.getHeader(PROFILE_CHANNEL_HEADER)));

    try {
      ProfileContextHolder.set(lookupKey);
      filterChain.doFilter(request, response);
    } finally {
      ProfileContextHolder.clear();
    }
  }

  private boolean requiresProfileContext(String path) {
    if (path == null) {
      return false;
    }
    return path.startsWith("/api/llm") || path.startsWith("/api/flows");
  }

  private String part(String key, int index) {
    String[] parts = key.split(":", 2);
    if (parts.length != 2) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, PROFILE_KEY_HEADER + " must be namespace:reference");
    }
    return parts[index];
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Profile handle is invalid");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeOptional(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
  }
}
