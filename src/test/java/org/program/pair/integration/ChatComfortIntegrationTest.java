package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.chat.dto.MessageDto;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Lot D5 — confort de messagerie.
 *
 * <p>Trois ajouts sans parenté, et une même question posée à chacun : que
 * garde-t-on, et pendant combien de temps ? L'indicateur de saisie ne garde rien,
 * le partage de position garde trente minutes au plus, la sourdine et l'archivage
 * gardent jusqu'à ce qu'on les défasse.
 */
class ChatComfortIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * Simulé pour observer ce que la sourdine coupe.
     *
     * <p>{@code notifyPushOnly} n'écrit rien en base — c'est tout son objet, un
     * message ayant déjà son fil et son compteur. Il n'y a donc rien à interroger
     * après coup : le seul moyen de vérifier qu'une push est partie, ou ne l'est
     * pas, est de regarder l'appel lui-même.
     */
    @MockitoBean NotificationService notificationService;

    // — partage de position ponctuel —

    @Test
    void unPartage_doitRendreUnPointEtSonEcheance() {
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        MessageDto shared = shareLocation(alice, conv, Map.of("lat", 48.5734, "lng", 7.7521));

        assertThat(shared.locationLat()).isEqualTo(48.5734);
        assertThat(shared.locationLng()).isEqualTo(7.7521);
        assertThat(shared.locationExpiresAt()).isNotNull();
    }

    @Test
    void unPartage_doitApparaitreDansLeFil_commeNimporteQuelMessage() {
        // C'est la propriété qui empêche de suivre quelqu'un à son insu : il n'y
        // a pas de canal discret, un partage est une bulle dans la conversation.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        UUID sharedId = shareLocation(alice, conv, Map.of("lat", 48.5734, "lng", 7.7521)).id();

        assertThat(messages(bob, conv)).extracting(MessageDto::id).contains(sharedId);
    }

    @Test
    void auDelaDeTrenteMinutes_lePartage_doitEtreRefuse_pasRabote() {
        // Raboter en silence laisserait l'appelant croire qu'il a obtenu la durée
        // demandée, et le garde-fou ne serait vrai que dans la base.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        webTestClient.post().uri("/api/conversations/{id}/location", conv)
            .headers(h -> h.setBearerAuth(alice.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("lat", 48.5734, "lng", 7.7521, "expiresInMinutes", 60))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void unPartageEchu_neDoitPlusRendreDePoint_maisGarderSonMessage() {
        // Le cœur du lot. Le message reste — il dit qu'une position a été
        // partagée, ce qui est vrai — mais le point n'est plus servi.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        UUID sharedId = shareLocation(alice, conv, Map.of("lat", 48.5734, "lng", 7.7521)).id();
        expire(sharedId);

        MessageDto seen = messages(bob, conv).stream()
            .filter(m -> m.id().equals(sharedId)).findFirst().orElseThrow();

        assertThat(seen.locationLat()).isNull();
        assertThat(seen.locationLng()).isNull();
        assertThat(seen.locationExpiresAt()).isNull();
        assertThat(seen.content()).isNotBlank();
    }

    @Test
    void unPartageEchu_doitCesserDetreServi_avantMemeQueLaBaseSoitNettoyee() {
        // La lecture décide, le balayage nettoie. Ici la base porte encore les
        // coordonnées : si le test passe, c'est bien la lecture qui a tranché.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        UUID sharedId = shareLocation(alice, conv, Map.of("lat", 48.5734, "lng", 7.7521)).id();
        expire(sharedId);

        Double stillInDb = jdbcTemplate.queryForObject(
            "SELECT location_lat FROM messages WHERE id = ?", Double.class, sharedId);
        assertThat(stillInDb).isNotNull();

        assertThat(messages(bob, conv).stream()
            .filter(m -> m.id().equals(sharedId)).findFirst().orElseThrow()
            .locationLat()).isNull();
    }

    @Test
    void unMessageOrdinaire_neDoitPorterAucunPoint() {
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        MessageDto plain = send(alice, conv, "Je suis en route");

        assertThat(plain.locationLat()).isNull();
        assertThat(plain.locationExpiresAt()).isNull();
    }

    // — sourdine —

    @Test
    void laSourdine_doitRetirerLeFilDuTotal_sansRetirerSonPropreDecompte() {
        // La sourdine coupe l'émission, pas la réception : le message est bien
        // arrivé, et le fil le dit. C'est le badge qui se tait.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        send(bob, conv, "Tu viens ?");
        assertThat(unreadTotal(alice)).isEqualTo(1);

        settings(alice, conv, Map.of("muted", true));

        assertThat(unreadTotal(alice)).isZero();
        assertThat(summary(alice, conv).unreadCount()).isEqualTo(1);
        assertThat(summary(alice, conv).muted()).isTrue();
    }

    @Test
    void leTotal_doitResterEgalALaSommeDesFilsNiEnSourdineNiArchives() {
        // L'invariant que le client utilise pour vérifier son badge. Sans les
        // deux drapeaux sur le résumé, les deux calculs divergeraient sans que
        // rien ne dise pourquoi.
        Account alice = account();
        Account bob = account();
        Account carol = account();

        UUID withBob = conversationBetween(alice, bob);
        UUID withCarol = conversationBetween(alice, carol);
        send(bob, withBob, "un");
        send(carol, withCarol, "deux");

        settings(alice, withBob, Map.of("muted", true));

        long sum = conversations(alice, false).stream()
            .filter(c -> !c.muted() && !c.archived())
            .mapToLong(ConversationSummaryDto::unreadCount)
            .sum();

        assertThat(sum).isEqualTo(unreadTotal(alice));
    }

    @Test
    void laSourdine_doitCouperLaPush_etElleSeule() {
        // Le vrai objet de la sourdine, et le seul point du lot qui pouvait
        // échouer sans qu'aucune réponse HTTP ne le montre : le message continue
        // d'arriver et de s'afficher, mais plus rien ne sonne.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        send(bob, conv, "un");
        verify(notificationService, timeout(3000)).notifyPushOnly(
            eq(alice.id), eq(bob.id), eq(NotificationType.NEW_MESSAGE), any());

        settings(alice, conv, Map.of("muted", true));
        clearInvocations(notificationService);

        send(bob, conv, "deux");

        // Reçu : la sourdine coupe l'émission, pas la réception.
        assertThat(messages(alice, conv)).hasSize(2);
        // Mais rien n'a sonné.
        verify(notificationService, after(1500).never()).notifyPushOnly(
            eq(alice.id), any(), any(), any());
    }

    @Test
    void laSourdine_doitSeDefaire() {
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);
        send(bob, conv, "Tu viens ?");

        settings(alice, conv, Map.of("muted", true));
        assertThat(unreadTotal(alice)).isZero();

        settings(alice, conv, Map.of("muted", false));
        assertThat(unreadTotal(alice)).isEqualTo(1);
    }

    @Test
    void laSourdine_neDoitConcernerQueCeluiQuiLaPose() {
        // Deux personnes d'un même fil ne le classent pas pareil.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        settings(alice, conv, Map.of("muted", true));

        send(alice, conv, "Toujours partant ?");
        assertThat(unreadTotal(bob)).isEqualTo(1);
        assertThat(summary(bob, conv).muted()).isFalse();
    }

    // — archivage —

    @Test
    void archiver_doitSortirLeFilDeLaListe_etLeRangerDansLautre() {
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        assertThat(conversationIds(alice, false)).contains(conv);
        assertThat(conversationIds(alice, true)).doesNotContain(conv);

        settings(alice, conv, Map.of("archived", true));

        assertThat(conversationIds(alice, false)).doesNotContain(conv);
        assertThat(conversationIds(alice, true)).contains(conv);
    }

    @Test
    void unMessageRecu_neDoitPasDesarchiverLeFil() {
        // Le choix qui distingue ce dépôt de plusieurs messageries : ranger le fil
        // dont on veut se débarrasser n'aurait aucun effet s'il en ressortait à la
        // première réception, puisque c'est justement celui qui reçoit.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);
        settings(alice, conv, Map.of("archived", true));

        send(bob, conv, "Tu es là ?");

        assertThat(conversationIds(alice, false)).doesNotContain(conv);
        assertThat(conversationIds(alice, true)).contains(conv);
    }

    @Test
    void archiver_doitAussiRetirerLeFilDuTotal() {
        // Un badge qui pointerait vers un fil rangé hors de vue serait un nombre
        // qu'on ne saurait pas d'où faire retomber.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);
        send(bob, conv, "Tu es là ?");

        settings(alice, conv, Map.of("archived", true));

        assertThat(unreadTotal(alice)).isZero();
    }

    @Test
    void lesDeuxReglages_doiventEtreIndependants() {
        // Un champ absent reste inchangé : les deux commandes vivent sur deux
        // écrans différents, et régler l'une ne doit pas remettre l'autre à zéro.
        Account alice = account();
        Account bob = account();
        UUID conv = conversationBetween(alice, bob);

        settings(alice, conv, Map.of("muted", true));
        ConversationSummaryDto after = settings(alice, conv, Map.of("archived", true));

        assertThat(after.muted()).isTrue();
        assertThat(after.archived()).isTrue();
    }

    // — helpers —

    private record Account(UUID id, String token) {}

    private void expire(UUID messageId) {
        jdbcTemplate.update(
            "UPDATE messages SET location_expires_at = now() - interval '1 minute' WHERE id = ?",
            messageId);
    }

    private MessageDto shareLocation(Account from, UUID conversationId, Map<String, Object> body) {
        return webTestClient.post().uri("/api/conversations/{id}/location", conversationId)
            .headers(h -> h.setBearerAuth(from.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isCreated()
            .expectBody(MessageDto.class).returnResult().getResponseBody();
    }

    private ConversationSummaryDto settings(Account who, UUID conversationId, Map<String, Object> body) {
        return webTestClient.patch().uri("/api/conversations/{id}/settings", conversationId)
            .headers(h -> h.setBearerAuth(who.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(ConversationSummaryDto.class).returnResult().getResponseBody();
    }

    private long unreadTotal(Account who) {
        Map<?, ?> body = webTestClient.get().uri("/api/conversations/unread-count")
            .headers(h -> h.setBearerAuth(who.token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) body.get("unreadCount")).longValue();
    }

    private List<ConversationSummaryDto> conversations(Account who, boolean archived) {
        return webTestClient.get()
            .uri(b -> b.path("/api/conversations").queryParam("archived", archived).build())
            .headers(h -> h.setBearerAuth(who.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(ConversationSummaryDto.class).returnResult().getResponseBody();
    }

    private List<UUID> conversationIds(Account who, boolean archived) {
        return conversations(who, archived).stream().map(ConversationSummaryDto::id).toList();
    }

    private ConversationSummaryDto summary(Account who, UUID conversationId) {
        return conversations(who, false).stream()
            .filter(c -> c.id().equals(conversationId))
            .findFirst()
            .orElseGet(() -> conversations(who, true).stream()
                .filter(c -> c.id().equals(conversationId))
                .findFirst().orElseThrow());
    }

    private List<MessageDto> messages(Account who, UUID conversationId) {
        return webTestClient.get().uri("/api/conversations/{id}/messages", conversationId)
            .headers(h -> h.setBearerAuth(who.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(MessageDto.class).returnResult().getResponseBody();
    }

    private MessageDto send(Account from, UUID conversationId, String content) {
        return webTestClient.post().uri("/api/conversations/{id}/messages", conversationId)
            .headers(h -> h.setBearerAuth(from.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("conversationId", conversationId.toString(), "content", content))
            .exchange().expectStatus().isCreated()
            .expectBody(MessageDto.class).returnResult().getResponseBody();
    }

    private UUID conversationBetween(Account initiator, Account other) {
        ConversationSummaryDto conv = webTestClient.post().uri("/api/conversations")
            .headers(h -> h.setBearerAuth(initiator.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("targetUserId", other.id.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(ConversationSummaryDto.class).returnResult().getResponseBody();
        assertThat(conv).isNotNull();
        return conv.id();
    }

    private Account account() {
        String email = uniqueEmail("chat");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Bavard"))
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
        return new Account(id, auth.accessToken());
    }
}
