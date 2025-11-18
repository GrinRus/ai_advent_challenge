package com.aiadvent.backend.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProfileLookupId implements Serializable {

  @Column(name = "namespace", length = 64, nullable = false)
  private String namespace;

  @Column(name = "reference", length = 128, nullable = false)
  private String reference;

  protected ProfileLookupId() {}

  public ProfileLookupId(String namespace, String reference) {
    this.namespace = namespace;
    this.reference = reference;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getReference() {
    return reference;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProfileLookupId that = (ProfileLookupId) o;
    return Objects.equals(namespace, that.namespace) && Objects.equals(reference, that.reference);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, reference);
  }
}
