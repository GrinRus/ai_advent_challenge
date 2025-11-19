package com.aiadvent.backend.profile.security;

import com.aiadvent.backend.profile.service.ProfileContextHolder;
import com.aiadvent.backend.profile.service.ProfileLookupKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(
    prefix = "app.profile.security",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProfileContextFilter extends OncePerRequestFilter {

  private final ProfileSecurityService profileSecurityService;

  public ProfileContextFilter(ProfileSecurityService profileSecurityService) {
    this.profileSecurityService = profileSecurityService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!requiresProfileContext(path)) {
      filterChain.doFilter(request, response);
      return;
    }

    ProfileLookupKey lookupKey = null;
    try {
      lookupKey = profileSecurityService.resolveProfileKey(request);
      profileSecurityService.ensureDevAccess(request);
      if (isAdminPath(path)) {
        profileSecurityService.ensureAdminRole(lookupKey);
      }
      ProfileContextHolder.set(lookupKey);
      filterChain.doFilter(request, response);
    } catch (ResponseStatusException ex) {
      response.sendError(ex.getStatusCode().value(), ex.getReason());
      return;
    } finally {
      ProfileContextHolder.clear();
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    return !requiresProfileContext(path);
  }

  private boolean requiresProfileContext(String path) {
    if (path == null) {
      return false;
    }
    return path.startsWith("/api/llm")
        || path.startsWith("/api/flows")
        || path.startsWith("/api/profile")
        || path.startsWith("/api/admin");
  }

  private boolean isAdminPath(String path) {
    return path != null && path.startsWith("/api/admin");
  }
}
