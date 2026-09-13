package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.map.dto.MapActivitiesResponse;
import org.program.pair.domain.map.dto.MapActivityMarkerDto;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

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

    /**
     * Un lieu qui n'appartient qu'à cette classe.
     *
     * <p>Huit classes d'intégration posaient leurs créneaux sur le même point de
     * Strasbourg (48.5734, 7.7521). Tant que chacune avait sa base, elles ne se
     * voyaient pas. Depuis que la suite partage un conteneur, le fil lu dans un
     * rayon de 20 km rend aussi les créneaux des autres : les assertions d'ordre
     * se font bousculer par des créneaux dont ce test ignore l'existence, et
     * l'échec ne dit rien de ce qu'il vérifie.
     *
     * <p>Déplacer le décor est plus sûr que filtrer les résultats : le test
     * continue de lire le fil tel que l'application le rend, sans assertion
     * affaiblie pour contourner le bruit.
     */
    private static final double LAT = 47.3220;   // Dijon
    private static final double LNG = 5.0415;

    @Autowired ActivityRepository activityRepository;
    @Autowired NotificationService notificationService;
    @Autowired JdbcTemplate jdbcTemplate;

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

    // — les deux surfaces refermées le 2026-08-19 —

    @Test
    void laCarteDesActivites_doitMasquerLesOrganisateursBloques() {
        // Cette route est restée ouverte sans jeton jusqu'au 2026-08-19, et sans
        // appelant identifié il n'y avait personne à qui masquer quoi que ce
        // soit. Un profil bloqué qui garde ses activités sur la carte est un
        // profil qui n'est pas bloqué.
        Account alice = account();
        Account bob = account();
        // Un lieu à lui : voir publishSlotAt.
        publishSlotAt(bob, 48.6402, 7.8123);

        assertThat(mapOrganizerIds(alice)).contains(bob.id);

        block(alice, bob);

        assertThat(mapOrganizerIds(alice)).doesNotContain(bob.id);
        // Bilatéral : c'est alice qui a bloqué, et pourtant bob ne se voit pas
        // retirer moins qu'elle — il garde ses propres marqueurs, mais perd les
        // siens à elle. Alice n'ayant rien publié, on vérifie le sens qui compte
        // ici : bob se voit toujours lui-même, le filtre n'ayant pas débordé.
        assertThat(mapOrganizerIds(bob)).contains(bob.id);
    }

    @Test
    void laCarteDesActivites_neDoitPlusRepondreSansJeton() {
        webTestClient.get()
            .uri("/api/map/activities")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void lesProgrammesDunProfil_doiventDevenirIntrouvables() {
        // La fiche de profil refusait déjà ; la liste de ses programmes, servie
        // au même écran, répondait encore — et elle nomme son auteur, ses lieux
        // et ses horaires.
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        webTestClient.get()
            .uri("/api/users/{id}/programs", alice.id)
            .headers(h -> h.setBearerAuth(bob.token))
            .exchange()
            .expectStatus().isNotFound();

        webTestClient.get()
            .uri("/api/users/{id}/programs", bob.id)
            .headers(h -> h.setBearerAuth(alice.token))
            .exchange()
            .expectStatus().isNotFound();
    }

    // — inscriptions croisées : ce que le blocage retire (P-BL-05) —
    //
    // Le blocage fermait la porte d'entrée et laissait entrer ceux qui étaient
    // déjà dedans : les deux personnes restaient inscrites l'une chez l'autre, et
    // se retrouvaient sur le trottoir devant la salle.

    @Test
    void bloquerLOrganisateur_doitRetirerMonInscriptionASesCreneauxAVenir() {
        Account host = account();
        Account inscrit = account();
        Account enAttente = account();

        UUID slotId = publishSlotWithCapacity(host, 1);
        join(inscrit, slotId);
        joinWaitlist(enAttente, slotId);

        assertThat(mySlotIds(inscrit)).contains(slotId);

        block(inscrit, host);

        assertThat(mySlotIds(inscrit))
            .as("le créneau quitte « mes créneaux » : c'est le changement de "
                + "réponse le plus lourd de ce lot pour l'application publiée")
            .doesNotContain(slotId);

        // Et la place rendue profite à qui l'attendait : un retrait qui ne ferait
        // pas remonter la file laisserait une place libre derrière quelqu'un.
        assertThat(slotSeenBy(enAttente, slotId).myParticipationStatus())
            .isEqualTo("CONFIRMED");
    }

    @Test
    void bloquerUnInscrit_doitRetirerSonInscriptionAMonCreneau() {
        // L'autre sens, et c'est la même règle : peu importe qui bloque. Un
        // retrait qui dépendrait du sens laisserait les deux personnes face à
        // face une fois sur deux.
        Account host = account();
        Account inscrit = account();

        UUID slotId = publishSlot(host);
        join(inscrit, slotId);

        block(host, inscrit);

        assertThat(mySlotIds(inscrit)).doesNotContain(slotId);
        assertThat(slotSeenBy(host, slotId).participantCount())
            .as("le compteur de places suit le retrait")
            .isZero();
    }

    @Test
    void bloquerLOrganisateur_doitAussiRetirerMaPlaceEnFileDAttente() {
        // Attendre une place, c'est avoir organisé sa journée autour de cette
        // séance : la file compte parmi les trois statuts que le retrait touche.
        Account host = account();
        Account occupant = account();
        Account enAttente = account();

        UUID slotId = publishSlotWithCapacity(host, 1);
        join(occupant, slotId);
        joinWaitlist(enAttente, slotId);

        assertThat(mySlotIds(enAttente)).contains(slotId);

        block(enAttente, host);

        assertThat(mySlotIds(enAttente)).doesNotContain(slotId);
        // L'occupant n'a rien perdu : le retrait ne concerne que les deux
        // personnes du blocage.
        assertThat(mySlotIds(occupant)).contains(slotId);
    }

    @Test
    void uneInscriptionAToutUnProgramme_doitSurvivreAuBlocage() {
        // La frontière du retrait : sans créneau désigné, une inscription n'est
        // pas une rencontre prévue — il n'y a ni heure ni lieu où se croiser. La
        // porte d'entrée s'occupe de la suite.
        Account host = account();
        Account inscrit = account();

        UUID slotId = publishSlot(host);
        UUID programId = slotSeenBy(inscrit, slotId).programId();
        joinProgram(inscrit, programId);

        block(inscrit, host);

        assertThat(myActiveProgramCount(inscrit)).isEqualTo(1);
    }

    /**
     * La fiche se referme pour la personne bloquée — <b>et l'organisateur garde
     * la sienne</b>.
     *
     * <p><b>La seconde moitié est la correction d'une assertion fausse</b>, et il
     * faut dire laquelle pour que personne ne la réécrive. Elle demandait un 404 à
     * l'organisateur aussi, « comme la fiche de profil ». Or ici l'organisateur du
     * créneau <i>est</i> celui qui a bloqué : le refus porte sur le couple
     * (appelant, organisateur), et ce couple vaut deux fois la même personne.
     * {@code BlockFilterService.blocked} rend faux quand les deux identifiants
     * sont égaux — on ne se bloque pas soi-même, et la base l'interdit.
     *
     * <p>Le parallèle avec la fiche de profil ne tenait donc pas : là-bas, les
     * deux côtés sont deux personnes. Ici, exiger le 404 aurait voulu dire « un
     * organisateur perd la fiche de son propre créneau dès qu'il bloque
     * quelqu'un » — ce qui ferait échouer la publication elle-même,
     * {@code QuickSlotService.create} rendant son résultat par
     * {@code getSlot(scheduleId, auteur)}, et fermerait à l'organisateur tous ses
     * écrans de séance. C'est l'assertion qui avait tort, pas le filtre.
     *
     * <p>La symétrie qui compte — celle où celui qui bloque n'est pas
     * l'organisateur — est vérifiée juste après, par
     * {@link #bloquerLOrganisateur_doitAussiFermerLaFicheDeSonCreneau}.
     */
    @Test
    void unePersonneBloquee_doitRecevoir404SurLaFicheDuCreneau() {
        Account host = account();
        Account viewer = account();
        UUID slotId = publishSlot(host);

        // Inscrite d'abord : la fiche doit se refermer même pour quelqu'un qui y
        // était, et pas seulement pour un passant.
        join(viewer, slotId);
        block(host, viewer);

        expectCreneauIntrouvable(viewer, slotId);

        // L'organisateur, lui, garde son propre créneau : le blocage ne se
        // retourne pas contre celui qui l'a posé.
        assertThat(slotSeenBy(host, slotId).scheduleId()).isEqualTo(slotId);
    }

    @Test
    void bloquerLOrganisateur_doitAussiFermerLaFicheDeSonCreneau() {
        // L'autre sens du blocage sur la même lecture : c'est l'inscrite qui
        // bloque, et la fiche se referme pour elle aussi. Elle reçoit le même
        // refus muet, bien qu'elle sache pourquoi : une lecture n'a rien à
        // expliquer, et la forme nommée (USER_BLOCKED) est réservée aux gestes —
        // s'inscrire, écrire —, où elle explique un échec qu'on vient de
        // provoquer.
        Account host = account();
        Account viewer = account();
        UUID slotId = publishSlot(host);

        join(viewer, slotId);
        block(viewer, host);

        expectCreneauIntrouvable(viewer, slotId);
    }

    // — fil de diffusion : le masquage ne doit pas tomber sur l'écran qui y mène —

    @Test
    void unFilDeDiffusion_neDoitPlusRemettreLesMessagesDeLAuteurBloque() {
        Account host = account();
        Account participant = account();

        UUID slotId = publishSlot(host);
        UUID programId = slotSeenBy(participant, slotId).programId();
        joinProgram(participant, programId);

        broadcast(host, programId, "Rendez-vous devant l'entrée principale.");

        UUID threadId = broadcastThreadId(participant);
        assertThat(messageContents(participant, threadId))
            .contains("Rendez-vous devant l'entrée principale.");

        block(participant, host);

        assertThat(messageContents(participant, threadId))
            .as("un fil de groupe ne se ferme pas, il cesse de porter ce que "
                + "quelqu'un de masqué y écrit")
            .isEmpty();

        // Et l'aperçu de la liste ne doit pas rendre par la fenêtre ce que le fil
        // vient de retirer par la porte.
        assertThat(broadcastThreadPreview(participant)).isNull();
    }

    // — notifications : ce que le blocage ne doit pas supprimer —

    @Test
    void uneAnnulation_doitAtteindreSonDestinataire_malgreLeBlocage() {
        // Le filtre supprimait tout, annulations comprises : quelqu'un traversait
        // la ville pour une séance qui n'avait pas lieu, parce que le message qui
        // l'en prévenait avait été supprimé au nom de sa protection. Une critique
        // ne « fait voir » personne — elle dit qu'un engagement n'a plus lieu.
        Account alice = account();
        Account bob = account();
        block(alice, bob);

        // L'ordinaire d'abord, la critique ensuite : au moment où la seconde est
        // écrite, la première a eu tout le temps de l'être si elle devait l'être.
        notificationService.notify(alice.id, bob.id, NotificationType.NEW_FOLLOWER,
            Map.of("followerName", "Bob"));
        notificationService.notify(alice.id, bob.id, NotificationType.SLOT_CANCELLED,
            Map.of("programTitle", "Séance annulée"));

        assertThat(pollNotificationCount(alice.id, "SLOT_CANCELLED"))
            .as("une notification critique passe malgré le blocage")
            .isEqualTo(1);

        assertThat(notificationCount(alice.id, "NEW_FOLLOWER"))
            .as("une notification ordinaire reste supprimée")
            .isZero();
    }

    // — helpers —

    private List<UUID> mapOrganizerIds(Account viewer) {
        MapActivitiesResponse response = webTestClient.get()
            .uri(b -> b.path("/api/map/activities").queryParam("limit", 500).build())
            .headers(h -> h.setBearerAuth(viewer.token))
            .exchange().expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.activities().stream()
            .map(MapActivityMarkerDto::organizerId)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

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
        return publishSlotAt(host, LAT, LNG);
    }

    /**
     * Publie à un lieu choisi.
     *
     * <p>Les marqueurs de la carte regroupent les créneaux par (activité, lieu
     * arrondi à ~111 m) et n'exposent qu'un organisateur « représentatif » par
     * marqueur, choisi par date. Deux comptes qui publient au même endroit se
     * fondent donc en un seul marqueur, et le second devient invisible sans
     * qu'aucun blocage y soit pour quelque chose : un test sur ce filtre doit
     * donner à sa victime un lieu à elle.
     */
    private UUID publishSlotAt(Account host, double lat, double lng) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(host.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, lat, lng,
                "1 avenue de l'Europe", null, "Strasbourg", null, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    /** Un créneau à une seule place : de quoi faire vivre une file d'attente. */
    private UUID publishSlotWithCapacity(Account host, int maxParticipants) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(host.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", maxParticipants,
                null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private void join(Account account, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(account.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange().expectStatus().is2xxSuccessful();
    }

    private void joinWaitlist(Account account, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/waitlist", slotId)
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().is2xxSuccessful();
    }

    /**
     * Inscription au <b>programme entier</b>, sans créneau désigné.
     *
     * <p>Corps vide explicite plutôt qu'absent : {@code scheduleId} est nul dans
     * les deux cas, mais un POST sans type de contenu dépend du gestionnaire
     * d'arguments, là où {@code {}} en JSON ne dépend de rien.
     */
    private void joinProgram(Account account, UUID programId) {
        webTestClient.post().uri("/api/programs/{id}/join", programId)
            .headers(h -> h.setBearerAuth(account.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private int myActiveProgramCount(Account account) {
        List<Map> programs = webTestClient.get()
            .uri(b -> b.path("/api/users/me/programs").queryParam("status", "ACTIVE").build())
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();
        return programs == null ? 0 : programs.size();
    }

    private List<UUID> mySlotIds(Account account) {
        List<SlotFeedItemDto> mine = webTestClient.get()
            .uri("/api/slots/mine")
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return mine == null ? List.of() : mine.stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    /**
     * Le refus doit être <b>indistinguable</b> de celui d'un créneau qui n'existe
     * pas : même statut, même code, même message.
     *
     * <p>C'est ce qui empêche de déduire du refus que le créneau existe — donc
     * qu'un blocage est en jeu. La comparaison se fait avec un identifiant tiré
     * au hasard, et pour le <b>même appelant</b> : deux réponses lues par la même
     * personne, dont une seule concerne un créneau réel.
     */
    private void expectCreneauIntrouvable(Account caller, UUID slotId) {
        assertThat(refusDeFiche(caller, slotId))
            .isEqualTo(refusDeFiche(caller, UUID.randomUUID()));
    }

    /** Le couple (code, message) d'un 404 sur la fiche d'un créneau. */
    private String refusDeFiche(Account caller, UUID slotId) {
        Map<?, ?> body = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(caller.token))
            .exchange().expectStatus().isNotFound()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        // L'horodatage est écarté : il diffère d'une réponse à l'autre sans rien
        // apprendre de la ressource.
        return body.get("code") + " / " + body.get("message");
    }

    private SlotFeedItemDto slotSeenBy(Account account, UUID slotId) {
        SlotFeedItemDto slot = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot;
    }

    private void broadcast(Account author, UUID programId, String content) {
        webTestClient.post().uri("/api/programs/{id}/broadcasts", programId)
            .headers(h -> h.setBearerAuth(author.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("content", content))
            .exchange().expectStatus().isCreated();
    }

    private Map<?, ?> broadcastThread(Account account) {
        List<Map> threads = webTestClient.get().uri("/api/conversations")
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();
        assertThat(threads).isNotNull();
        return threads.stream()
            .filter(t -> "PROGRAM_BROADCAST".equals(t.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("aucun fil de diffusion pour ce compte"));
    }

    private UUID broadcastThreadId(Account account) {
        return UUID.fromString(String.valueOf(broadcastThread(account).get("id")));
    }

    private Object broadcastThreadPreview(Account account) {
        return broadcastThread(account).get("lastMessageContent");
    }

    private List<String> messageContents(Account account, UUID conversationId) {
        List<Map> messages = webTestClient.get()
            .uri(b -> b.path("/api/conversations/{id}/messages")
                .queryParam("limit", 50).build(conversationId))
            .headers(h -> h.setBearerAuth(account.token))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();
        return messages == null
            ? List.of()
            : messages.stream().map(m -> String.valueOf(m.get("content"))).toList();
    }

    /**
     * Attente bornée de l'écriture d'une notification.
     *
     * <p>{@code notify} est {@code @Async} : la ligne n'existe pas au retour de
     * l'appel. La colonne de date de cette table est {@code sent_at}, et non
     * {@code created_at}.
     */
    private long pollNotificationCount(UUID userId, String type) {
        long count = 0;
        for (int attempt = 0; attempt < 50 && count == 0; attempt++) {
            count = notificationCount(userId, type);
            if (count == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return count;
    }

    private long notificationCount(UUID userId, String type) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
            Long.class, userId, type);
        return count == null ? 0 : count;
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
