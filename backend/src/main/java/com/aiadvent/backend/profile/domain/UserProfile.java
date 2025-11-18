package com.aiadvent.backend.profile.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_profile")
public class UserProfile {

  public enum CommunicationMode {
    TEXT,
    VOICE,
    HYBRID
  }

  @Id
  @GeneratedValue(generator = "uuid2")
  @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
  private UUID id;

  @NotBlank
  @Size(max = 64)
  @Column(name = "namespace", nullable = false, length = 64)
  private String namespace;

  @NotBlank
  @Size(max = 128)
  @Column(name = "reference", nullable = false, length = 128)
  private String reference;

  @NotBlank
  @Size(max = 255)
  @Column(name = "display_name", nullable = false, length = 255)
  private String displayName;

  @NotBlank
  @Size(max = 16)
  @Column(name = "locale", nullable = false, length = 16)
  private String locale = "en";

  @NotBlank
  @Size(max = 64)
  @Column(name = "timezone", nullable = false, length = 64)
  private String timezone = "UTC";

  @Enumerated(EnumType.STRING)
  @Column(name = "communication_mode", nullable = false, length = 32)
  private CommunicationMode communicationMode = CommunicationMode.TEXT;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "habits", nullable = false, columnDefinition = "jsonb")
  private List<String> habits = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "anti_patterns", nullable = false, columnDefinition = "jsonb")
  private List<String> antiPatterns = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "work_hours", nullable = false, columnDefinition = "jsonb")
  private JsonNode workHours = JsonNodeFactory.instance.objectNode();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
  private JsonNode metadata = JsonNodeFactory.instance.objectNode();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @PrePersist
  void onPersist() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone;
  }

  public CommunicationMode getCommunicationMode() {
    return communicationMode;
  }

  public void setCommunicationMode(CommunicationMode communicationMode) {
    this.communicationMode = communicationMode;
  }

  public List<String> getHabits() {
    return habits;
  }

  public void setHabits(List<String> habits) {
    this.habits = habits != null ? new ArrayList<>(habits) : new ArrayList<>();
  }

  public List<String> getAntiPatterns() {
    return antiPatterns;
  }

  public void setAntiPatterns(List<String> antiPatterns) {
    this.antiPatterns = antiPatterns != null ? new ArrayList<>(antiPatterns) : new ArrayList<>();
  }

  public JsonNode getWorkHours() {
    return workHours;
  }

  public void setWorkHours(JsonNode workHours) {
    this.workHours = workHours;
  }

  public JsonNode getMetadata() {
    return metadata;
  }

  public void setMetadata(JsonNode metadata) {
    this.metadata = metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
