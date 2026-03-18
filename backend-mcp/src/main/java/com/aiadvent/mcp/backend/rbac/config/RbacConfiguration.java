package com.aiadvent.mcp.backend.rbac.config;

import com.aiadvent.mcp.backend.rbac.matcher.RbacPathMatcher;
import com.aiadvent.mcp.backend.rbac.persistence.AuditLogRepository;
import com.aiadvent.mcp.backend.rbac.service.AuditLogService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for RBAC system.
 */
@Configuration
@EnableConfigurationProperties(RbacProperties.class)
public class RbacConfiguration {

    @Bean
    public RbacPathMatcher rbacPathMatcher(RbacProperties rbacProperties) {
        return new RbacPathMatcher(rbacProperties);
    }

    @Bean
    public AuditLogService auditLogService(AuditLogRepository auditLogRepository) {
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "audit-log-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        return new AuditLogService(auditLogRepository, executor);
    }
}
