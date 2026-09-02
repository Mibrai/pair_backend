package org.program.pair.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.search.SearchLog;
import org.program.pair.domain.search.dto.RecentSearchDto;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.SearchLogRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Demande 6 de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md : GET
 * /api/search/recent expose un id stable, et DELETE /api/search/recent/{id}
 * supprime une entrée précise.
 *
 * <p>Un test par critère d'acceptation du §6 du prompt client. Les entrées
 * d'historique sont insérées en base plutôt que via POST /api/search, pour que
 * l'ordre et le contenu soient déterministes — sauf le dernier test, qui vérifie
 * justement le chemin réel de création.
 *
 * <p>Deux comptes seulement pour toute la classe, créés une fois via un garde
 * statique : l'inscription est limitée à 5/heure/IP (RateLimiter).
 */
class RecentSearchDeletionIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired SearchLogRepository searchLogRepository;

    private static final String OWNER_EMAIL = "recent-search-owner@pair.app";
    private static final String OTHER_EMAIL = "recent-search-other@pair.app";

    private static boolean accountsCreated = false;
    private static String ownerToken;
    private static String otherToken;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        if (!accountsCreated) {
            ownerToken = registerAndLogin(OWNER_EMAIL);
            otherToken = registerAndLogin(OTHER_EMAIL);
            accountsCreated = true;
        }
        owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        other = userRepository.findByEmail(OTHER_EMAIL).orElseThrow();

        // deleteAll(Iterable) plutôt que deleteByUserId : la requête dérivée n'est
        // pas transactionnelle vue depuis le test (elle ne l'est que via le
        // @Transactional de SearchHistoryService).
        searchLogRepository.deleteAll(searchLogRepository.findByUserIdOrderBySearchedAtDesc(owner.getId()));
        searchLogRepository.deleteAll(searchLogRepository.findByUserIdOrderBySearchedAtDesc(other.getId()));
    }

    @Test
    void getRecent_doitRenvoyerUnIdStableEntreDeuxAppels() {
        logSearch(owner, "yoga", Instant.now().minus(1, ChronoUnit.MINUTES));
        logSearch(owner, "escalade", Instant.now().minus(2, ChronoUnit.MINUTES));

        List<RecentSearchDto> first = getRecent(ownerToken);
        List<RecentSearchDto> second = getRecent(ownerToken);

        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(entry -> assertThat(entry.id()).isNotNull());
        assertThat(second.stream().map(RecentSearchDto::id).toList())
            .as("l'id d'une entrée ne doit pas changer d'un chargement à l'autre")
            .isEqualTo(first.stream().map(RecentSearchDto::id).toList());
    }

    @Test
    void deleteRecentById_doitRenvoyer204_etFaireDisparaitreLEntree() {
        logSearch(owner, "yoga", Instant.now().minus(1, ChronoUnit.MINUTES));
        logSearch(owner, "escalade", Instant.now().minus(2, ChronoUnit.MINUTES));

        UUID targetId = getRecent(ownerToken).stream()
            .filter(e -> "yoga".equals(e.query()))
            .findFirst().orElseThrow().id();

        webTestClient.delete()
            .uri("/api/search/recent/{id}", targetId)
            .headers(h -> h.setBearerAuth(ownerToken))
            .exchange()
            .expectStatus().isNoContent();

        List<RecentSearchDto> after = getRecent(ownerToken);
        assertThat(after).extracting(RecentSearchDto::id).doesNotContain(targetId);
        assertThat(after).extracting(RecentSearchDto::query).containsExactly("escalade");
    }

    @Test
    void deleteRecentById_surLEntreeDunAutreUtilisateur_doitEchouerEtNeRienSupprimer() {
        logSearch(other, "natation", Instant.now().minus(1, ChronoUnit.MINUTES));
        UUID foreignId = getRecent(otherToken).get(0).id();

        webTestClient.delete()
            .uri("/api/search/recent/{id}", foreignId)
            .headers(h -> h.setBearerAuth(ownerToken))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEARCH_HISTORY_ENTRY_NOT_FOUND");

        assertThat(getRecent(otherToken))
            .as("l'entrée de la victime doit être intacte")
            .extracting(RecentSearchDto::id).containsExactly(foreignId);
    }

    @Test
    void deleteRecentById_deuxFoisSurLeMemeId_doitRenvoyer404LaSecondeFois() {
        logSearch(owner, "yoga", Instant.now().minus(1, ChronoUnit.MINUTES));
        UUID targetId = getRecent(ownerToken).get(0).id();

        webTestClient.delete().uri("/api/search/recent/{id}", targetId)
            .headers(h -> h.setBearerAuth(ownerToken))
            .exchange().expectStatus().isNoContent();

        // Choix assumé : la suppression n'est pas idempotente (cf. SearchHistoryService).
        webTestClient.delete().uri("/api/search/recent/{id}", targetId)
            .headers(h -> h.setBearerAuth(ownerToken))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void deleteRecentSansId_doitViderLHistoriqueDuSeulAppelant() {
        logSearch(owner, "yoga", Instant.now().minus(1, ChronoUnit.MINUTES));
        logSearch(owner, "escalade", Instant.now().minus(2, ChronoUnit.MINUTES));
        logSearch(other, "natation", Instant.now().minus(1, ChronoUnit.MINUTES));

        webTestClient.delete()
            .uri("/api/search/recent")
            .headers(h -> h.setBearerAuth(ownerToken))
            .exchange()
            .expectStatus().isNoContent();

        assertThat(getRecent(ownerToken)).isEmpty();
        assertThat(getRecent(otherToken))
            .as("vider son historique ne doit pas toucher celui des autres")
            .extracting(RecentSearchDto::query).containsExactly("natation");
    }

    @Test
    void refaireUneRechercheSupprimee_doitLaReintroduireDansLHistorique() {
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);

        runSearch("yoga");
        UUID firstId = getRecent(ownerToken).get(0).id();

        webTestClient.delete().uri("/api/search/recent/{id}", firstId)
            .headers(h -> h.setBearerAuth(ownerToken))
            .exchange().expectStatus().isNoContent();
        assertThat(getRecent(ownerToken)).isEmpty();

        runSearch("yoga");

        List<RecentSearchDto> after = getRecent(ownerToken);
        assertThat(after).as("la suppression n'est pas une liste noire").hasSize(1);
        assertThat(after.get(0).query()).isEqualTo("yoga");
        assertThat(after.get(0).id())
            .as("la nouvelle entrée est une entrée neuve, pas la ressuscitée")
            .isNotEqualTo(firstId);
    }

    // — helpers —

    private void logSearch(User user, String query, Instant searchedAt) {
        searchLogRepository.save(SearchLog.builder()
            .user(user)
            .rawQuery(query)
            .searchMethod("test")
            .resultsCount(0)
            .searchedAt(searchedAt)
            .build());
    }

    private List<RecentSearchDto> getRecent(String token) {
        return webTestClient.get()
            .uri("/api/search/recent")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(RecentSearchDto.class)
            .returnResult()
            .getResponseBody();
    }

    private void runSearch(String query) {
        webTestClient.post()
            .uri("/api/search")
            .headers(h -> h.setBearerAuth(ownerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest(query, 48.8566, 2.3522, 20000))
            .exchange()
            .expectStatus().isOk();
    }

    private String registerAndLogin(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
