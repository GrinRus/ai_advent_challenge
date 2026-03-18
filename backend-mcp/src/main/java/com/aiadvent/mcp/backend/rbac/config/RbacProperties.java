package com.aiadvent.mcp.backend.rbac.config;

import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for RBAC system.
 */
@ConfigurationProperties(prefix = "rbac")
public class RbacProperties {

    private boolean enabled = false;
    private boolean auditOnly = true;
    private int cacheTtlMinutes = 5;
    private List<PathRule> protectedPaths = Collections.emptyList();
    private List<String> publicPaths = Collections.emptyList();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAuditOnly() {
        return auditOnly;
    }

    public void setAuditOnly(boolean auditOnly) {
        this.auditOnly = auditOnly;
    }

    public int getCacheTtlMinutes() {
        return cacheTtlMinutes;
    }

    public void setCacheTtlMinutes(int cacheTtlMinutes) {
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    public List<PathRule> getProtectedPaths() {
        return protectedPaths;
    }

    public void setProtectedPaths(List<PathRule> protectedPaths) {
        this.protectedPaths = protectedPaths;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    /**
     * Path rule for protected routes.
     */
    public static class PathRule {
        private String path;
        private List<String> methods = Collections.emptyList();
        private List<String> requiredRoles = Collections.emptyList();

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public List<String> getMethods() {
            return methods;
        }

        public void setMethods(List<String> methods) {
            this.methods = methods;
        }

        public List<String> getRequiredRoles() {
            return requiredRoles;
        }

        public void setRequiredRoles(List<String> requiredRoles) {
            this.requiredRoles = requiredRoles;
        }
    }
}
