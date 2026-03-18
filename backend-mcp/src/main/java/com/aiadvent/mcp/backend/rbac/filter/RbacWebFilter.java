package com.aiadvent.mcp.backend.rbac.filter;

import com.aiadvent.mcp.backend.rbac.domain.ProfileIdentity;
import com.aiadvent.mcp.backend.rbac.domain.Role;
import com.aiadvent.mcp.backend.rbac.matcher.RbacPathMatcher;
import com.aiadvent.mcp.backend.rbac.service.AuditLogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter for RBAC enforcement on mutation APIs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RbacWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacWebFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PROFILE_KEY_HEADER = "X-Profile-Key";

    private final RbacPathMatcher pathMatcher;
    private final AuditLogService auditLogService;

    public RbacWebFilter(RbacPathMatcher pathMatcher, AuditLogService auditLogService) {
        this.pathMatcher = pathMatcher;
        this.auditLogService = auditLogService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String method = request.getMethod().name();

        // Skip non-mutation methods
        if (pathMatcher.shouldSkipMethod(method)) {
            return chain.filter(exchange);
        }

        // Skip public paths
        if (pathMatcher.isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // Only protect mutation methods or explicitly configured paths
        if (!pathMatcher.isMutationMethod(method) && !pathMatcher.isProtectedPath(path, method)) {
            return chain.filter(exchange);
        }

        // Extract identity from header
        String profileKey = request.getHeaders().getFirst(PROFILE_KEY_HEADER);
        if (profileKey == null || profileKey.isBlank()) {
            return sendForbiddenResponse(exchange, "Missing or empty X-Profile-Key header");
        }

        ProfileIdentity identity = parseProfileKey(profileKey);
        if (identity == null) {
            return sendForbiddenResponse(exchange, "Invalid X-Profile-Key format. Expected: namespace:reference");
        }

        // Get required roles for this path/method
        List<String> requiredRoles = pathMatcher.getRequiredRoles(path, method);

        // Get roles for identity (mock/stub for now - will be replaced with profile service in I3)
        Set<Role> userRoles = getMockRoles(identity);

        // Check if user has any of the required roles
        boolean hasRequiredRole = requiredRoles.isEmpty() ||
                requiredRoles.stream().anyMatch(role ->
                        userRoles.stream().anyMatch(userRole -> userRole.name().equalsIgnoreCase(role)));

        if (!hasRequiredRole) {
            auditLogService.logDenied(
                    identity,
                    "ACCESS",
                    path,
                    method,
                    path,
                    "Insufficient permissions. Required roles: " + requiredRoles,
                    userRoles
            );
            return sendForbiddenResponse(exchange,
                    "Access denied. Required roles: " + requiredRoles);
        }

        // Log allowed access
        auditLogService.logAccess(
                identity,
                "ACCESS",
                path,
                method,
                path,
                true,
                userRoles
        );

        return chain.filter(exchange);
    }

    /**
     * Parse profile key from header format: namespace:reference
     */
    private ProfileIdentity parseProfileKey(String profileKey) {
        if (profileKey == null || !profileKey.contains(":")) {
            return null;
        }
        String[] parts = profileKey.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        try {
            return new ProfileIdentity(parts[0].trim(), parts[1].trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Mock role service - returns roles based on namespace.
     * This will be replaced with actual profile service integration in I3.
     */
    private Set<Role> getMockRoles(ProfileIdentity identity) {
        // Mock logic: admin namespace gets ADMIN role, test namespace gets USER role
        String ns = identity.namespace().toLowerCase();
        if (ns.equals("admin") || ns.equals("github")) {
            return Set.of(Role.ADMIN, Role.USER, Role.VIEWER);
        } else if (ns.equals("user") || ns.equals("local")) {
            return Set.of(Role.USER, Role.VIEWER);
        } else if (ns.equals("viewer")) {
            return Set.of(Role.VIEWER);
        }
        // Default: VIEWER only
        return Set.of(Role.VIEWER);
    }

    /**
     * Send a 403 Forbidden response with JSON body.
     */
    private Mono<Void> sendForbiddenResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("status", 403);
        errorBody.put("error", "Forbidden");
        errorBody.put("message", message);
        errorBody.put("path", exchange.getRequest().getPath().value());

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorBody);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to write error response", e);
            byte[] bytes = ("{\"status\":403,\"error\":\"Forbidden\"}").getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        }
    }
}
