package com.aiadvent.mcp.backend.rbac.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity representing an RBAC audit log entry.
 */
@Entity
@Table(name = "rbac_audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "actor_namespace", length = 32, nullable = false)
    private String actorNamespace;

    @Column(name = "actor_reference", length = 128, nullable = false)
    private String actorReference;

    @Column(name = "action", length = 32, nullable = false)
    private String action;

    @Column(name = "target_resource", length = 256)
    private String targetResource;

    @Column(name = "target_profile_namespace", length = 32)
    private String targetProfileNamespace;

    @Column(name = "target_profile_reference", length = 128)
    private String targetProfileReference;

    @Column(name = "result", length = 16, nullable = false)
    private String result;

    @Column(name = "http_method", length = 8)
    private String httpMethod;

    @Column(name = "http_path", length = 512)
    private String httpPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private JsonNode metadata;

    public UUID getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getActorNamespace() {
        return actorNamespace;
    }

    public void setActorNamespace(String actorNamespace) {
        this.actorNamespace = actorNamespace;
    }

    public String getActorReference() {
        return actorReference;
    }

    public void setActorReference(String actorReference) {
        this.actorReference = actorReference;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetResource() {
        return targetResource;
    }

    public void setTargetResource(String targetResource) {
        this.targetResource = targetResource;
    }

    public String getTargetProfileNamespace() {
        return targetProfileNamespace;
    }

    public void setTargetProfileNamespace(String targetProfileNamespace) {
        this.targetProfileNamespace = targetProfileNamespace;
    }

    public String getTargetProfileReference() {
        return targetProfileReference;
    }

    public void setTargetProfileReference(String targetProfileReference) {
        this.targetProfileReference = targetProfileReference;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getHttpPath() {
        return httpPath;
    }

    public void setHttpPath(String httpPath) {
        this.httpPath = httpPath;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }

    @PrePersist
    void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }
}
