package com.aiadvent.mcp.backend.rbac.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Component scan configuration for RBAC package.
 * Active in all non-test profiles.
 */
@Configuration
@Profile("!test")
@ComponentScan("com.aiadvent.mcp.backend.rbac")
public class RbacConfig {
}
