package com.aiadvent.mcp.backend.rbac.matcher;

import com.aiadvent.mcp.backend.rbac.config.RbacProperties;
import com.aiadvent.mcp.backend.rbac.config.RbacProperties.PathRule;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

/**
 * Path matching logic for protected routes.
 */
public class RbacPathMatcher {

    private final RbacProperties properties;
    private final PathMatcher pathMatcher;

    public RbacPathMatcher(RbacProperties properties) {
        this.properties = properties;
        this.pathMatcher = new AntPathMatcher();
    }

    /**
     * Check if the given path is public (excluded from RBAC).
     *
     * @param path the request path
     * @return true if the path is public
     */
    public boolean isPublicPath(String path) {
        if (properties.getPublicPaths() == null) {
            return false;
        }
        return properties.getPublicPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * Check if the given path and method should be protected.
     *
     * @param path   the request path
     * @param method the HTTP method
     * @return true if the path/method requires RBAC check
     */
    public boolean isProtectedPath(String path, String method) {
        if (properties.getProtectedPaths() == null) {
            return isMutationMethod(method);
        }
        return properties.getProtectedPaths().stream()
                .anyMatch(rule -> matchesRule(rule, path, method));
    }

    /**
     * Get required roles for the given path and method.
     *
     * @param path   the request path
     * @param method the HTTP method
     * @return list of required roles, empty if none defined
     */
    public List<String> getRequiredRoles(String path, String method) {
        if (properties.getProtectedPaths() == null) {
            return Collections.emptyList();
        }
        return properties.getProtectedPaths().stream()
                .filter(rule -> matchesRule(rule, path, method))
                .findFirst()
                .map(PathRule::getRequiredRoles)
                .orElse(Collections.emptyList());
    }

    /**
     * Check if the HTTP method is a mutation method (POST, PUT, DELETE, PATCH).
     *
     * @param method the HTTP method
     * @return true if it's a mutation method
     */
    public boolean isMutationMethod(String method) {
        if (method == null) {
            return false;
        }
        String upper = method.toUpperCase();
        return upper.equals("POST") || upper.equals("PUT")
                || upper.equals("DELETE") || upper.equals("PATCH");
    }

    /**
     * Check if the HTTP method should be skipped (GET, HEAD, OPTIONS).
     *
     * @param method the HTTP method
     * @return true if the method should be skipped
     */
    public boolean shouldSkipMethod(String method) {
        if (method == null) {
            return false;
        }
        String upper = method.toUpperCase();
        return upper.equals("GET") || upper.equals("HEAD") || upper.equals("OPTIONS");
    }

    private boolean matchesRule(PathRule rule, String path, String method) {
        if (rule.getPath() == null || !pathMatcher.match(rule.getPath(), path)) {
            return false;
        }
        if (rule.getMethods() == null || rule.getMethods().isEmpty()) {
            return true;
        }
        return rule.getMethods().stream()
                .anyMatch(m -> m.equalsIgnoreCase(method));
    }
}
