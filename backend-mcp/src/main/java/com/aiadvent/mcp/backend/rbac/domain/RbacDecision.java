package com.aiadvent.mcp.backend.rbac.domain;

import java.util.Objects;

/**
 * Immutable record representing an RBAC decision.
 *
 * @param allowed whether the action is allowed
 * @param reason  the reason for the decision (nullable)
 */
public record RbacDecision(boolean allowed, String reason) {

    public RbacDecision {
        // reason can be null, no validation needed
    }

    /**
     * Creates an allowed decision with no reason.
     *
     * @return an allowed RBAC decision
     */
    public static RbacDecision allow() {
        return new RbacDecision(true, null);
    }

    /**
     * Creates a denied decision with the given reason.
     *
     * @param reason the reason for denial
     * @return a denied RBAC decision
     */
    public static RbacDecision deny(String reason) {
        return new RbacDecision(false, Objects.requireNonNull(reason, "reason must not be null for denied decisions"));
    }
}
