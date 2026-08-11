package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.Mockito.when;

/**
 * Une carte vide et une carte en panne ne doivent pas se ressembler.
 *
 * <p>Elles se ressemblaient : {@code getAllActivitiesForMap} enveloppait tout
 * son corps dans un {@code catch (Exception)} qui rendait une liste vide en
 * {@code 200}. L'échafaudage avait été posé le 2026-07-04 pour survivre à une
 * {@code LazyInitializationException} que le commit suivant a corrigée par un
 * {@code JOIN FETCH} ; la cause est morte, le masque avait survécu cinq
 * semaines.
 *
 * <p>Le client, de son côté, terminait ses deux chargements de carte par un
 * {@code catchError} nu et comptait sur le statut HTTP pour trancher — que ce
 * {@code catch} lui retirait. Une panne de la surface la plus visible de l'app
 * était donc silencieuse des deux côtés à la fois, exactement comme l'incident
 * média du 2026-08-11.
 *
 * <p>Les deux tests ci-dessous interrogent <b>la même route avec le même
 * dépôt simulé</b> : c'est ce qui rend la frontière explicite. Sans données,
 * {@code 200} et une liste vide. En défaillance, {@code 500 INTERNAL_ERROR} —
 * journalisé côté serveur avec le {@code rid:} du MDC, donc corrélable avec le
 * {@code X-Request-Id} que le client consigne.
 */
class MapActivitiesErrorPathIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean ScheduleRepository scheduleRepository;

    @Test
    void zoneSansAucuneDonnee_doitRendre200EtUneListeVide() {
        when(scheduleRepository.findAllWithActivityDetails()).thenReturn(List.of());

        webTestClient.get()
            .uri("/api/map/activities")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.activities").isArray()
            .jsonPath("$.activities.length()").isEqualTo(0)
            .jsonPath("$.totalInBounds").isEqualTo(0)
            .jsonPath("$.truncated").isEqualTo(false)
            // Le centre par défaut reste servi : une carte vide doit quand même
            // s'ouvrir quelque part.
            .jsonPath("$.defaultCenter").exists();
    }

    @Test
    void defaillanceDuDepot_doitRendre500EtPasUneCarteVide() {
        when(scheduleRepository.findAllWithActivityDetails())
            .thenThrow(new DataAccessResourceFailureException("base injoignable"));

        webTestClient.get()
            .uri("/api/map/activities")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is5xxServerError()
            // L'en-tête est posé avant la chaîne de filtres, donc présent même en
            // erreur : c'est la clé sous laquelle chercher la trace serveur.
            .expectHeader().exists("X-Request-Id")
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.activities").doesNotExist();
    }

    /**
     * L'autre bord de la frontière : une erreur de paramètre reste un 400 nommé,
     * jamais un 500 ni une carte vide. {@code validate()} est appelé avant tout
     * accès au dépôt — le mock n'est donc même pas sollicité.
     */
    @Test
    void parametreInvalide_doitRendreUn400Nomme() {
        webTestClient.get()
            .uri(b -> b.path("/api/map/activities").queryParam("zoom", 25).build())
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MAP_ZOOM_OUT_OF_RANGE");
    }
}
