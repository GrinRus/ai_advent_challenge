package com.aiadvent.backend.profile.security;

import com.aiadvent.backend.profile.config.ProfileDevAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "app.profile.dev", name = "enabled", havingValue = "true")
public class ProfileDevAuthFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Profile-Auth";

  private final String devToken;

  public ProfileDevAuthFilter(ProfileDevAuthProperties properties) {
    this.devToken = properties.getToken();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!StringUtils.hasText(devToken)) {
      filterChain.doFilter(request, response);
      return;
    }

    String headerValue = request.getHeader(HEADER);
    if (StringUtils.hasText(headerValue) && devToken.equals(headerValue.trim())) {
      request.setAttribute("profile.dev-authenticated", Boolean.TRUE);
    }

    filterChain.doFilter(request, response);
  }
}
