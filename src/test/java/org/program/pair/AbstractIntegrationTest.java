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
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Image custom avec PostGIS + pgvector, construite depuis pair-postgres/Dockerfile.postgres
    // (l'image publique pgvector/pgvector n'embarque pas PostGIS, requis par le schéma).
    private static final ImageFromDockerfile POSTGRES_IMAGE = new ImageFromDockerfile("pair-postgres-test", false)
        .withDockerfile(Paths.get("pair-postgres/Dockerfile.postgres"));

    /**
     * <b>Un seul conteneur pour toute la suite</b>, démarré à la première classe
     * et jamais arrêté.
     *
     * <p>Il portait {@code @Container} et {@code @Testcontainers} : JUnit
     * démarrait alors un conteneur <b>par classe</b> et l'arrêtait derrière, soit
     * 95 démarrages et 95 exécutions des migrations pour une suite complète.
     *
     * <p>Ce n'est pourtant pas le gain principal, et il faut le dire pour que
     * personne ne se trompe de levier en relisant : mesuré le 07/09, le
     * démarrage du conteneur ne pèse que <b>1,70 s</b> sur les 19,05 s d'une
     * classe. Le reste, ce sont la JVM forkée et le contexte Spring — 8,87 s.
     *
     * <p>Le vrai rôle de ce singleton est d'être <b>ce qui rend le cache de
     * contexte de Spring utilisable</b>. Spring réutilise un contexte entre
     * classes de test lorsque sa configuration est identique ; tant que chaque
     * classe recevait sa propre URL JDBC par {@code @DynamicPropertySource},
     * deux contextes ne pouvaient jamais être les mêmes. URL commune, contexte
     * partagé, et les 8,87 s ne se paient plus qu'une fois. Cela suppose
     * {@code reuseForks=true} dans le pom : sans lui, chaque classe repart dans
     * une JVM neuve et jette le cache.
     *
     * <p>Pas de {@code withReuse(true)} ni de {@code ~/.testcontainers.properties} :
     * une seule JVM porte désormais la suite, donc un simple champ statique
     * suffit. Ryuk nettoie le conteneur à la sortie de la JVM.
     *
     * <p><b>Ce que cela change pour les tests</b> : la base n'est plus vierge au
     * début de chaque classe. Une classe voit ce que les précédentes ont écrit.
     * Les tests qui comptent des lignes globalement, ou qui supposent une table
     * vide, doivent être rendus indépendants de l'ordre — c'est le prix, et il
     * est explicite.
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE.get())
                .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("pair_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("test-init.sql"); // active postgis + vector extensions (idempotent)

    static {
        postgres.start();
    }

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
    protected void resetRateLimiter() {
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

    /**
     * Un mobile français valide, différent à chaque appel.
     *
     * <p>Cinq classes désignaient {@code "0612345678"} comme contact d'urgence.
     * Tant que chacune avait sa base, aucune ne gênait les autres. Or le refus
     * d'un contact est enregistré <b>par numéro et pour tout le monde</b> — voir
     * {@code GuardianService.normaliserEtVerifierLeNumero} : un numéro qui a dit
     * non une fois ne peut plus jamais être désigné, par personne. Il a suffi
     * qu'une classe éprouve le refus sur ce numéro pour que toutes celles qui
     * passaient après reçoivent 422 GUARDIAN_CONTACT_REFUSED, à un endroit sans
     * rapport avec ce qu'elles vérifient — et l'ordre d'exécution décidait
     * lesquelles.
     *
     * <p>Le numéro n'a aucune importance pour ces tests : il leur faut un mobile
     * valide, pas celui-là. Le tirage évite qu'un test en empoisonne un autre.
     */
    protected static String uniqueMobile() {
        long suffixe = Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits() % 100_000_000L);
        return String.format("06%08d", suffixe);
    }
}
