package com.utkarsh.ratelimiter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Redis and MySQL for integration tests, using the same images as docker-compose.yml.
 * {@code @ServiceConnection} points Spring's datasource and Redis connection at the containers,
 * overriding application.yml, so tests never need local credentials.
 *
 * <p>Import with {@code @Import(InfrastructureContainers.class)}. Contexts with identical
 * configuration are cached, so the containers start once per test JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InfrastructureContainers {

    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    public static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(MYSQL_IMAGE).withDatabaseName("ratelimiter");
    }
}
