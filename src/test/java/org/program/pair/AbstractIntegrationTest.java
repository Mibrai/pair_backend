package org.program.pair;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Image custom avec PostGIS + pgvector, construite depuis pair-postgres/Dockerfile.postgres
    // (l'image publique pgvector/pgvector n'embarque pas PostGIS, requis par le schéma).
    private static final ImageFromDockerfile POSTGRES_IMAGE = new ImageFromDockerfile("pair-postgres-test", false)
        .withDockerfile(Paths.get("pair-postgres/Dockerfile.postgres"));

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE.get())
                .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("pair_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("test-init.sql"); // active postgis + vector extensions (idempotent)

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @LocalServerPort private int port;

    // Spring Boot 4 n'enregistre plus automatiquement un bean WebTestClient pour
    // @SpringBootTest(webEnvironment = RANDOM_PORT) : on le construit nous-mêmes.
    protected WebTestClient webTestClient;
    @Autowired protected ObjectMapper objectMapper;

    @BeforeEach
    void initWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(java.time.Duration.ofSeconds(30))
            // /v3/api-docs dépasse les 256 KB par défaut depuis que les schémas
            // sont réellement documentés (lot 7) : sans cette marge, les tests de
            // contrat OpenAPI échouent en DataBufferLimitException.
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build();
    }

    protected HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
