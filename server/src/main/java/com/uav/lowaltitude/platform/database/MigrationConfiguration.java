package com.uav.lowaltitude.platform.database;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {
    @Bean FlywayConfigurationCustomizer preserveAppliedMigrationLineage() {
        return MigrationLineage::configure;
    }
}
