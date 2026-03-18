package com.aiadvent.mcp.backend.rbac.service;

import com.aiadvent.mcp.backend.rbac.domain.ProfileIdentity;
import com.aiadvent.mcp.backend.rbac.domain.Role;
import com.aiadvent.mcp.backend.rbac.persistence.AuditLogEntity;
import com.aiadvent.mcp.backend.rbac.persistence.AuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Async audit logging service for RBAC operations.
 */
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AuditLogRepository auditLogRepository;
    private final ExecutorService executor;

    public AuditLogService(AuditLogRepository auditLogRepository, ExecutorService executor) {
        this.auditLogRepository = auditLogRepository;
        this.executor = executor;
    }

    /**
     * Log an access attempt (allowed or denied).
     *
     * @param identity    the actor identity
     * @param action      the action being performed
     * @param resource    the target resource
     * @param httpMethod  the HTTP method
     * @param httpPath    the HTTP path
     * @param allowed     whether access was allowed
     * @param roles       the roles the actor has
     */
    public void logAccess(
            ProfileIdentity identity,
            String action,
            String resource,
            String httpMethod,
            String httpPath,
            boolean allowed,
            Set<Role> roles) {

        executor.submit(() -> {
            try {
                AuditLogEntity entry = new AuditLogEntity();
                entry.setActorNamespace(identity.namespace());
                entry.setActorReference(identity.reference());
                entry.setAction(action);
                entry.setTargetResource(resource);
                entry.setResult(allowed ? "ALLOWED" : "DENIED");
                entry.setHttpMethod(httpMethod);
                entry.setHttpPath(httpPath);

                JsonNode metadata = objectMapper.valueToTree(
                        new AuditMetadata(roles != null ? roles.stream().map(Role::name).toList() : List.of()));
                entry.setMetadata(metadata);

                auditLogRepository.save(entry);
                log.debug("Audit log saved for {}: {} {} -> {}",
                        identity, httpMethod, httpPath, allowed ? "ALLOWED" : "DENIED");
            } catch (Exception e) {
                log.error("Failed to save audit log for {}: {}", identity, e.getMessage(), e);
            }
        });
    }

    /**
     * Log a denied access attempt.
     *
     * @param identity   the actor identity
     * @param action     the action being performed
     * @param resource   the target resource
     * @param httpMethod the HTTP method
     * @param httpPath   the HTTP path
     * @param reason     the reason for denial
     * @param roles      the roles the actor has
     */
    public void logDenied(
            ProfileIdentity identity,
            String action,
            String resource,
            String httpMethod,
            String httpPath,
            String reason,
            Set<Role> roles) {

        executor.submit(() -> {
            try {
                AuditLogEntity entry = new AuditLogEntity();
                entry.setActorNamespace(identity.namespace());
                entry.setActorReference(identity.reference());
                entry.setAction(action);
                entry.setTargetResource(resource);
                entry.setResult("DENIED");
                entry.setHttpMethod(httpMethod);
                entry.setHttpPath(httpPath);

                JsonNode metadata = objectMapper.valueToTree(
                        new AuditDeniedMetadata(
                                roles != null ? roles.stream().map(Role::name).toList() : List.of(),
                                reason));
                entry.setMetadata(metadata);

                auditLogRepository.save(entry);
                log.debug("Audit log saved for denied access {}: {} {} - reason: {}",
                        identity, httpMethod, httpPath, reason);
            } catch (Exception e) {
                log.error("Failed to save audit log for {}: {}", identity, e.getMessage(), e);
            }
        });
    }

    /**
     * Log a role change operation (GRANT/REVOKE).
     *
     * @param actorIdentity  the actor performing the change
     * @param targetIdentity the target profile being changed
     * @param action         the action (GRANT or REVOKE)
     * @param role           the role being granted/revoked
     * @param success        whether the operation succeeded
     */
    public void logRoleChange(
            ProfileIdentity actorIdentity,
            ProfileIdentity targetIdentity,
            String action,
            Role role,
            boolean success) {

        executor.submit(() -> {
            try {
                AuditLogEntity entry = new AuditLogEntity();
                entry.setActorNamespace(actorIdentity.namespace());
                entry.setActorReference(actorIdentity.reference());
                entry.setAction(action);
                entry.setTargetProfileNamespace(targetIdentity.namespace());
                entry.setTargetProfileReference(targetIdentity.reference());
                entry.setResult(success ? "SUCCESS" : "FAILED");

                JsonNode metadata = objectMapper.valueToTree(
                        new RoleChangeMetadata(role.name()));
                entry.setMetadata(metadata);

                auditLogRepository.save(entry);
                log.debug("Audit log saved for role change {}: {} role {} to {} -> {}",
                        actorIdentity, action, role, targetIdentity, success ? "SUCCESS" : "FAILED");
            } catch (Exception e) {
                log.error("Failed to save role change audit log: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Shutdown the executor service gracefully.
     */
    public void shutdown() {
        executor.shutdown();
    }

    // Metadata records for JSON serialization
    private record AuditMetadata(List<String> roles) {}

    private record AuditDeniedMetadata(List<String> roles, String reason) {}

    private record RoleChangeMetadata(String role) {}
}
