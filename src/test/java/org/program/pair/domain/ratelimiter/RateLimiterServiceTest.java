package org.program.pair.domain.ratelimiter;

import org.junit.jupiter.api.Test;
import org.program.pair.shared.exception.TooManyRequestsException;
import org.program.pair.shared.security.RateLimiterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class RateLimiterServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("redis.host", redis::getHost);
        registry.add("redis.port", () -> redis.getMappedPort(6379));
        registry.add("redis.enabled", () -> "true");
    }

    @Autowired
    RateLimiterService rateLimiterService;

    @Test
    void checkSearch_devraitBloquer_apres30RequetesParMinute() {
        UUID userId = UUID.randomUUID();

        // Les 30 premières requêtes doivent passer
        for (int i = 0; i < 30; i++) {
            assertThatCode(() -> rateLimiterService.checkSearch(userId))
                .doesNotThrowAnyException();
        }

        // La 31e requête doit être bloquée
        assertThatThrownBy(() -> rateLimiterService.checkSearch(userId))
            .isInstanceOf(TooManyRequestsException.class);
    }
}
