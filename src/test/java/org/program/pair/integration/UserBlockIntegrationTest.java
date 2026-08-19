package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot A3 — blocage d'utilisateur.
 *
 * <p>Le lot que la spécification désigne elle-même comme le plus facile à
 * rater : le blocage n'a de sens que s'il tient sur <b>toutes</b> les surfaces,
 * et il suffit d'en oublier une pour que les deux personnes se retrouvent.
 *
 * <p>Deux exigences sont vérifiées partout : le masquage est <b>bilatéral</b> —
 * peu importe qui a bloqué — et il reste <b>indétectable</b> par la personne
 * bloquée, qui ne doit jamais recevoir de refus nommé.
 */
class UserBlockIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;

    // — poser et lever —

    @Test
    void bloquer_puisDebloquer_doitEtreIdempotent() {
        Account alice = account();
        Account bob = account();

        block(alice, bob);
        block(alice, bob);   // rejouer ne doit pas échouer
        unblock(alice, bob);
        unblock(alice, bob);
    }

    @Test
    void seBloquerSoiMeme_doitEtreRefuse() {
        Account alice = account();

        webTestClient.post()
            .uri("/api/users/{id}/block", alice.id)
            .headers(h -> h.setBearerAuth(alice.token))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void laListeDesBloques_doitEtrePaginee_commeLesNotifications() {
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        webTestClient.get()
            .uri("/api/users/me/blocked")
            .headers(h -> h.setBearerAuth(alice.token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content").isArray()
            .jsonPath("$.page").exists()
            .jsonPath("$.content[0].userId").isEqualTo(bob.id.toString());
    }

    // — profil : 404, jamais 403 —

    @Test
    void leProfilDUnBloque_doitEtreIntrouvable_desDeuxCotes() {
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        // Côté bloqueur
        webTestClient.get().uri("/api/users/{id}", bob.id)
            .headers(h -> h.setBearerAuth(alice.token))
            .exchange().expectStatus().isNotFound();

        // Côté bloqué : le même 404, surtout pas un 403 qui dirait « il existe ».
        webTestClient.get().uri("/api/users/{id}", alice.id)
            .headers(h -> h.setBearerAuth(bob.token))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void leProfilDoitRevenir_apresDeblocage() {
        Account alice = account();
        Account bob = account();
        block(alice, bob);
        unblock(alice, bob);

        webTestClient.get().uri("/api/users/{id}", bob.id)
            .headers(h -> h.setBearerAuth(alice.token))
            .exchange().expectStatus().isOk();
    }

    // — recherche de personnes : la surface absente du tableau de la spec —

    @Test
    void unBloque_doitDisparaitreDeLaRechercheDePersonnes() {
        Account alice = account();
        Account bob = account();

        block(alice, bob);

        webTestClient.get()
            .uri(b -> b.path("/api/users").queryParam("query", bob.displayName).build())
            .headers(h -> h.setBearerAuth(alice.token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content[?(@.id == '" + bob.id + "')]").doesNotExist()
            // Le compteur doit suivre : annoncer un total qu'on ne rend pas
            // ferait boucler un client sur des pages qui rétrécissent.
            .jsonPath("$.page.totalElements").isEqualTo(0);
    }

    // — fil des créneaux —

    @Test
    void leCreneauDUnBloque_doitDisparaitreDuFil_desDeuxCotes() {
        Account host = account();
        Account viewer = account();
        UUID slotId = publishSlot(host);

        assertThat(feedIds(viewer)).contains(slotId);

        block(viewer, host);

        assertThat(feedIds(viewer)).doesNotContain(slotId);
        // Et dans l'autre sens : l'hôte ne doit pas voir les créneaux du bloqueur.
        UUID otherSlot = publishSlot(viewer);
        assertThat(feedIds(host)).doesNotContain(otherSlot);
    }

    @Test
    void rejoindreLeCreneauDUnBloqueur_doitRendreIntrouvable() {
        Account host = account();
        Account joiner = account();
        UUID slotId = publishSlot(host);

        // C'est l'hôte qui bloque : le demandeur ne doit rien apprendre.
        block(host, joiner);

        webTestClient.post()
            .uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(joiner.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void rejoindreLeCreneauDeQuelquUnQuOnABloque_doitDirePourquoi() {
        Account host = account();
        Account joiner = account();
        UUID slotId = publishSlot(host);

        // Ici c'est le demandeur qui a bloqué : il a le droit de savoir.
        block(joiner, host);

        webTestClient.post()
            .uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(joiner.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("USER_BLOCKED");
    }

    // — conversations —

    @Test
    void ouvrirUneConversationAvecUnBloqueur_doitRendreIntrouvable() {
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        webTestClient.post()
            .uri("/api/conversations")
            .headers(h -> h.setBearerAuth(bob.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("targetUserId", alice.id.toString()))
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void ouvrirUneConversationAvecQuelquUnQuOnABloque_doitDirePourquoi() {
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        webTestClient.post()
            .uri("/api/conversations")
            .headers(h -> h.setBearerAuth(alice.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("targetUserId", bob.id.toString()))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("USER_BLOCKED");
    }

    @Test
    void uneConversationExistante_doitDisparaitreDesDeuxCotes() {
        Account alice = account();
        Account bob = account();

        webTestClient.post()
            .uri("/api/conversations")
            .headers(h -> h.setBearerAuth(alice.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("targetUserId", bob.id.toString()))
            .exchange()
            .expectStatus().isCreated();

        assertThat(conversationCount(alice)).isEqualTo(1);
        assertThat(conversationCount(bob)).isEqualTo(1);

        block(alice, bob);

        assertThat(conversationCount(alice)).isZero();
        assertThat(conversationCount(bob)).isZero();
    }

    // — abonnements —

    @Test
    void lesAbonnements_doiventEtreRompusDesDeuxCotes() {
        Account alice = account();
        Account bob = account();

        subscribeToAuthor(alice, bob);
        subscribeToAuthor(bob, alice);

        block(alice, bob);

        assertThat(subscriptionCount(alice)).isZero();
        assertThat(subscriptionCount(bob)).isZero();
    }

    @Test
    void sAbonnerAUnBloqueur_doitRendreIntrouvable() {
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        webTestClient.post()
            .uri("/api/users/{id}/subscription", alice.id)
            .headers(h -> h.setBearerAuth(bob.token))
            .exchange()
            .expectStatus().isNotFound();
    }

    // — helpers —

    private record Account(UUID id, String token, String displayName) {}

    private Account account() {
        String email = uniqueEmail("block");
        String displayName = "Bloc" + UUID.randomUUID().toString().substring(0, 8);

        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", displayName))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();

        UUID id = UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(auth.accessToken()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        // Position publique : sans elle, ni la carte ni la recherche de personnes
        // ne rendraient ce compte, et les tests de masquage seraient vides de sens.
        webTestClient.post().uri("/api/map/location")
            .headers(h -> h.setBearerAuth(auth.accessToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("latitude", LAT, "longitude", LNG))
            .exchange().expectStatus().is2xxSuccessful();

        return new Account(id, auth.accessToken(), displayName);
    }

    private void block(Account blocker, Account blocked) {
        webTestClient.post().uri("/api/users/{id}/block", blocked.id)
            .headers(h -> h.setBearerAuth(blocker.token))
            .exchange().expectStatus().isNoContent();
    }

    private void unblock(Account blocker, Account blocked) {
        webTestClient.delete().uri("/api/users/{id}/block", blocked.id)
            .headers(h -> h.setBearerAuth(blocker.token))
            .exchange().expectStatus().isNoContent();
    }

    private UUID publishSlot(Account host) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(host.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", null, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private List<UUID> feedIds(Account viewer) {
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000).build())
            .headers(h -> h.setBearerAuth(viewer.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return feed.stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    private int conversationCount(Account account) {
        return webTestClient.get().uri("/api/conversations")
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody().size();
    }

    private void subscribeToAuthor(Account subscriber, Account author) {
        webTestClient.post().uri("/api/users/{id}/subscription", author.id)
            .headers(h -> h.setBearerAuth(subscriber.token))
            .exchange().expectStatus().is2xxSuccessful();
    }

    private int subscriptionCount(Account account) {
        Map<?, ?> body = webTestClient.get()
            .uri(b -> b.path("/api/users/me/subscriptions").build())
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        Object content = body.get("content");
        return content instanceof List<?> list ? list.size() : 0;
    }
}
