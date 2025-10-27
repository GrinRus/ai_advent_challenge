package com.aiadvent.backend.flow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FlowMigrationCliProperties.class})
public class FlowMigrationConfiguration {}
