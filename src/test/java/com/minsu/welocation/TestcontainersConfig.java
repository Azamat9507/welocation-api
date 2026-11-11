package com.minsu.welocation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

// One MySQL and one Redis for the whole test run. Spring keeps a context per distinct test configuration, and each
// context used to start its own pair: a dozen contexts were enough to run Docker Desktop (4 GB) out of memory, and
// it killed the dev database (2026-11-12). destroyMethod = "" keeps a closing context from stopping containers the
// other contexts still use; Testcontainers removes them when the JVM exits. Tests clean up their own rows.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer mysql() {
        return MYSQL;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return REDIS;
    }
}
