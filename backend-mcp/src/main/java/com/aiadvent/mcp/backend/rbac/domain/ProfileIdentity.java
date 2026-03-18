package com.aiadvent.mcp.backend.rbac.domain;

import java.util.Objects;

/**
 * Immutable identity record for a profile, consisting of namespace and reference.
 *
 * @param namespace the namespace of the profile (e.g., "github", "local")
 * @param reference the unique reference within the namespace
 */
public record ProfileIdentity(String namespace, String reference) {

    public ProfileIdentity {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
    }

    @Override
    public String toString() {
        return namespace + ":" + reference;
    }
}
