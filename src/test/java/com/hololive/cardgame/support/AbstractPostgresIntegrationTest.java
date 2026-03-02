package com.hololive.cardgame.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractPostgresIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(AbstractPostgresIntegrationTest.class);

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("holocardgame_test")
        .withUsername("holocard_test")
        .withPassword("holocard_test");
    private static final boolean USE_TESTCONTAINER = startContainerIfDockerAvailable();

    private static boolean startContainerIfDockerAvailable() {
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                log.info("Docker not available. Integration tests will use configured local datasource.");
                return false;
            }
            POSTGRES.start();
            log.info("Using Testcontainers PostgreSQL for integration tests: {}", POSTGRES.getJdbcUrl());
            return true;
        } catch (Exception ex) {
            log.warn("Failed to initialize Testcontainers PostgreSQL; fallback to local datasource.", ex);
            return false;
        }
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        if (!USE_TESTCONTAINER) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
