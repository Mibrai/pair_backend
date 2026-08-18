package org.program.pair;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.program.pair.shared.security.RateLimiter;
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
    @Autowired private RateLimiter rateLimiter;

    /**
     * Le limiteur est un singleton du contexte, et ses compteurs d'inscription
     * ne se vident jamais : sans cette remise à zéro, une classe de test n'a
     * droit qu'à cinq inscriptions au total, et ce sont les méthodes tirées en
     * dernier par JUnit qui reçoivent les 429 (voir {@link RateLimiter#reset()}).
     */
    @BeforeEach
    void resetRateLimiter() {
        rateLimiter.reset();
    }

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

    /**
     * Une adresse dont on est sûr qu'aucune autre méthode ne l'a déjà prise.
     *
     * <p>Le conteneur est monté une fois par classe et <b>rien ne nettoie la base
     * entre deux méthodes</b> : un compte créé par la première méthode est encore
     * là pour la deuxième. Deux méthodes qui enregistrent la même adresse
     * fonctionnent donc séparément et échouent ensemble, et c'est celle que JUnit
     * tire en second qui reçoit le {@code 409} — l'échec se déplace quand l'ordre
     * change, ce qui le fait passer pour de l'instabilité alors qu'il est
     * parfaitement déterministe. Le cas se produit aussi <b>à l'intérieur</b>
     * d'une seule méthode {@code @ParameterizedTest}, où chaque jeu de paramètres
     * rejoue l'enregistrement.
     *
     * <p>Le préfixe reste lisible dans les journaux ; c'est le suffixe qui garantit
     * l'unicité. À utiliser partout où un test enregistre un compte dont l'adresse
     * exacte n'est pas l'objet de l'assertion.
     */
    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@pair.app";
    }
}
