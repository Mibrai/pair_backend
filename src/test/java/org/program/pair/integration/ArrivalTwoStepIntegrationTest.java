package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchOutboundJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.IncidentRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'arrivée à deux temps : la personne déclare, l'organisateur valide, le code
 * naît ensuite — et se valide tout seul si personne ne touche rien.
 *
 * <p>Deux tests portent l'essentiel du lot, et ce ne sont pas ceux du chemin
 * nominal :
 *
 * <ul>
 *   <li>{@link #arriveeDeclaree_neDoitPasEtreClasseePerdueEnChemin()} — sans la
 *       suspension de la boucle aller, déclarer son arrivée à T+40 fait classer
 *       « perdu en chemin » cinq minutes plus tard, ce qui referme la veille pour
 *       de bon ;</li>
 *   <li>{@link #sansGesteDeLhote_laValidationTombeTouteSeule()} — le garde-fou
 *       sans lequel la validation par l'organisateur ne serait pas livrable : elle
 *       ferait dépendre la naissance du code de retour d'un tiers.</li>
 * </ul>
 */
class ArrivalTwoStepIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired WatchOutboundJob outboundJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    // ─── premier temps : déclarer ─────────────────────────────────────────────

    @Test
    void declarerSonArrivee_neTirePasDeCode_etNeChangePasLetat() {
        Scene s = scene();

        webTestClient.post().uri("/api/watches/{id}/arrival/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isAccepted();

        Watch w = watch(s.watchId());
        assertThat(w.getArrivalClaimedAt()).isNotNull();
        assertThat(w.getArrivalConfirmedAt()).isNull();
        // L'état ne bouge pas : c'est la demande du client, et sa raison est que
        // WatchState.parse rend ARMED sur tout état inconnu — un état neuf ferait
        // retomber les app anciennes sur « en attente d'arrivée ».
        assertThat(w.getState()).isEqualTo(WatchState.ARMED);

        // Aucun code : c'est la validation qui ouvre ce droit.
        webTestClient.post().uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_ARRIVAL_NOT_CONFIRMED");
    }

    /**
     * L'échéance de la bascule est rendue par le serveur, pas calculée sur
     * l'appareil : c'est la seule façon que les deux côtés parlent du même instant,
     * et la même raison que {@code deadlineAt}. Le client l'affiche avant le geste
     * — « sans réponse de ton hôte, ta présence sera validée à … ».
     */
    @Test
    void laDeclaration_doitPorterLheureDeSaValidationAutomatique() {
        Scene s = scene();
        Instant avant = Instant.now();

        declarer(s);

        Map<?, ?> dto = veille(s);
        assertThat(dto.get("arrivalClaimedAt")).isNotNull();
        assertThat(dto.get("arrivalConfirmedAt")).isNull();

        Instant auto = Instant.parse(String.valueOf(dto.get("arrivalAutoConfirmAt")));
        assertThat(auto).isAfter(avant.plus(14, ChronoUnit.MINUTES));
        assertThat(auto).isBefore(avant.plus(16, ChronoUnit.MINUTES));
    }

    @Test
    void declarerDeuxFois_estUnConflitDetat_pasUneFaute() {
        Scene s = scene();
        declarer(s);

        webTestClient.post().uri("/api/watches/{id}/arrival/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_ARRIVAL_ALREADY_CLAIMED");
    }

    // ─── second temps : valider ───────────────────────────────────────────────

    @Test
    void lhoteValide_laVeillePasseSurPlace_etLeCodeNaitEnsuite() {
        Scene s = scene();
        declarer(s);

        webTestClient.post()
            .uri("/api/schedules/{s}/arrivals/{p}/confirm", s.scheduleId(), s.participationId())
            .headers(h -> h.setBearerAuth(s.hote().token()))
            .exchange().expectStatus().isAccepted();

        Watch w = watch(s.watchId());
        assertThat(w.getState()).isEqualTo(WatchState.ON_SITE);
        assertThat(w.getArrivalConfirmedAt()).isNotNull();

        String code = String.valueOf(webTestClient.post()
            .uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
        assertThat(code).isNotBlank().hasSize(5);
    }

    /**
     * Le silence du 404, gardé : un créneau qu'on n'organise pas est introuvable,
     * jamais interdit. Un refus nommé confirmerait l'existence du créneau et la
     * position de la personne qui essaie.
     */
    @Test
    void validerSurUnCreneauQuiNestPasLeSien_doitEtreIntrouvable() {
        Scene s = scene();
        declarer(s);
        Compte etranger = compte("etranger");

        webTestClient.post()
            .uri("/api/schedules/{s}/arrivals/{p}/confirm", s.scheduleId(), s.participationId())
            .headers(h -> h.setBearerAuth(etranger.token()))
            .exchange().expectStatus().isNotFound();

        assertThat(watch(s.watchId()).getArrivalConfirmedAt()).isNull();
    }

    /**
     * <b>Le verbe de validation ne doit pas devenir un détecteur.</b>
     *
     * <p>Sur un inscrit qui n'a rien armé, il rend le même 202 qu'une validation
     * réussie. S'il rendait 404 ou 409, l'organisateur apprendrait qui se protège
     * en essayant, et tout le soin pris à rendre {@code NONE} indistinguable dans
     * la liste des inscrits ne servirait à rien : ce qui doit être indistinguable
     * n'est pas seulement la donnée, c'est le <b>geste disponible</b>.
     */
    @Test
    void validerQuelquunQuiNaRienArme_doitRepondreCommeUneValidationReussie() {
        Scene s = sceneSansVeille();

        webTestClient.post()
            .uri("/api/schedules/{s}/arrivals/{p}/confirm", s.scheduleId(), s.participationId())
            .headers(h -> h.setBearerAuth(s.hote().token()))
            .exchange().expectStatus().isAccepted();
    }

    // ─── le code de retour ────────────────────────────────────────────────────

    /**
     * Une seule fois, comme la réponse d'{@code arrival}. C'est ce qui garde vraie
     * la phrase que toute l'app répète — « ce code n'existe en clair qu'une seule
     * fois, sur ce téléphone-là ». Qui l'a perdu passe par le renvoi sous mot de
     * passe, qui régénère au lieu de rejouer.
     */
    @Test
    void leCode_neDoitEtreRemisQuUneFois() {
        Scene s = scene();
        declarer(s);
        valider(s);

        webTestClient.post().uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isOk();

        webTestClient.post().uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_CODE_ALREADY_CLAIMED");
    }

    @Test
    void leCode_nestRemisQuAuTitulaire() {
        Scene s = scene();
        declarer(s);
        valider(s);

        // L'organisateur a validé l'arrivée ; le code, lui, ne le regarde pas.
        webTestClient.post().uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.hote().token()))
            .exchange().expectStatus().isNotFound();
    }

    /**
     * Le code remis referme la veille, code de contrainte compris : c'est la preuve
     * que le déplacement du tirage — de la validation vers la remise — n'a rien
     * cassé de la clôture.
     */
    @Test
    void leCodeRemis_refermeLaVeille() {
        Scene s = scene();
        declarer(s);
        valider(s);

        String code = String.valueOf(webTestClient.post()
            .uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("duressCode", "SESAME"))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));

        webTestClient.post().uri("/api/watches/{id}/close", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();

        assertThat(watch(s.watchId()).getState()).isEqualTo(WatchState.CLOSED);
    }

    // ─── la boucle aller, et le garde-fou ─────────────────────────────────────

    /**
     * <b>Le test qui compte le plus de ce lot.</b>
     *
     * <p>La boucle aller prononce « perdu en chemin » à T+45 sur une veille restée
     * {@code ARMED}/{@code EN_ROUTE} — et une arrivée déclarée l'y laisse, puisque
     * le client demande un champ et pas un état. Sans la suspension, quelqu'un qui
     * déclare son arrivée à T+40 est classé perdu en chemin cinq minutes plus tard :
     * sa veille devient terminale, le code de retour ne peut plus jamais lui être
     * remis, et sa soirée n'est plus surveillée par rien. L'inverse exact de ce que
     * ce module existe pour faire.
     */
    @Test
    void arriveeDeclaree_neDoitPasEtreClasseePerdueEnChemin() {
        Scene s = scene();
        declarer(s);

        // Toutes les fenêtres de la boucle aller sont franchies, et largement.
        reculerBaseAller(s.watchId(), 90);
        for (int i = 0; i < 5; i++) {
            outboundJob.tick();
        }

        Watch w = watch(s.watchId());
        assertThat(w.getState()).isNotEqualTo(WatchState.NOT_ARRIVED);
        assertThat(incidentRepository.existsByWatchId(s.watchId())).isFalse();
    }

    /**
     * Et les relances s'arrêtent aussi : continuer à demander « tu y es ? » à
     * quelqu'un qui vient de dire qu'il y est serait la première chose que le
     * client nous rapporterait.
     */
    @Test
    void arriveeDeclaree_arreteLesDemandesDArrivee() {
        Scene s = scene();
        declarer(s);
        int avant = watch(s.watchId()).getArrivalPromptsSent();

        reculerBaseAller(s.watchId(), 90);
        outboundJob.tick();
        outboundJob.tick();

        assertThat(watch(s.watchId()).getArrivalPromptsSent()).isEqualTo(avant);
    }

    /**
     * Le garde-fou du §1.4, et la raison pour laquelle la validation par l'hôte est
     * livrable : nous avions refusé le code de séance le 02/09 parce qu'un geste
     * détenu par un tiers ferait de lui un point de pression. Passé le délai,
     * l'arrivée se valide sans que personne n'ait rien touché — un hôte absent,
     * distrait ou hostile ne peut pas retenir quelqu'un sans code de retour.
     */
    @Test
    void sansGesteDeLhote_laValidationTombeTouteSeule() {
        Scene s = scene();
        declarer(s);
        reculerDeclaration(s.watchId(), 16);

        outboundJob.tick();

        Watch w = watch(s.watchId());
        assertThat(w.getState()).isEqualTo(WatchState.ON_SITE);
        assertThat(w.getArrivalConfirmedAt()).isNotNull();

        webTestClient.post().uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isOk();
    }

    /**
     * La bascule ne tombe pas avant l'heure : sans cela, l'hôte n'aurait jamais
     * l'occasion de valider et le geste qu'on lui donne serait décoratif.
     */
    @Test
    void avantLeDelai_laValidationNeTombePas() {
        Scene s = scene();
        declarer(s);
        reculerDeclaration(s.watchId(), 10);

        outboundJob.tick();

        assertThat(watch(s.watchId()).getArrivalConfirmedAt()).isNull();
    }

    /**
     * Quelqu'un qui arrive <b>en avance</b> doit être validé comme les autres.
     *
     * <p>Le cas qui interdisait de greffer la bascule sur le balayage de la boucle
     * aller : celui-ci ne voit que les veilles dont {@code outboundBaseAt} est déjà
     * passé, or il vaut le début de la séance. Une déclaration faite dix minutes
     * avant l'heure n'y serait jamais entrée, et sa validation ne serait jamais
     * tombée.
     */
    @Test
    void arriveeDeclareeEnAvance_doitQuandMemeSeValider() {
        Scene s = scene();
        declarer(s);
        // Le début de séance est dans deux heures : la veille n'est pas dans le
        // champ du balayage des relances.
        assertThat(watch(s.watchId()).getOutboundBaseAt()).isAfter(Instant.now());
        reculerDeclaration(s.watchId(), 16);

        outboundJob.tick();

        assertThat(watch(s.watchId()).getArrivalConfirmedAt()).isNotNull();
    }

    // ─── l'insigne, et ce qu'il ne doit pas dire ──────────────────────────────

    /**
     * <b>La contrainte de confidentialité du §1.3, et elle est structurante.</b>
     *
     * <p>{@code NONE} doit vouloir dire exactement la même chose pour quelqu'un qui
     * n'a pas armé de veille et pour quelqu'un qui en a armé une sans déclarer son
     * arrivée. Sans cela, l'insigne devient un détecteur : l'organisateur apprend
     * qui se protège, ce que personne n'a accepté de lui dire.
     */
    @Test
    void avoirArmeSansDeclarer_doitEtreIndistinguableDeNavoirRienArme() {
        Scene avecVeille = scene();
        Compte sansVeille = compte("nu");
        rejoindre(sansVeille, avecVeille.hote(), avecVeille.scheduleId());

        List<?> inscrits = webTestClient.get()
            .uri("/api/slots/{id}/participants", avecVeille.scheduleId())
            .headers(h -> h.setBearerAuth(avecVeille.hote().token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();

        // Les deux inscrits sont là, et leurs deux blocs d'arrivée sont identiques.
        List<?> arrivees = inscrits.stream()
            .map(l -> ((Map<?, ?>) l).get("arrival"))
            .toList();
        assertThat(arrivees).hasSize(2);
        assertThat(arrivees).allSatisfy(bloc -> {
            Map<?, ?> a = (Map<?, ?>) bloc;
            assertThat(a.get("state")).isEqualTo("NONE");
            assertThat(a.get("claimedAt")).isNull();
            assertThat(a.get("confirmedAt")).isNull();
        });
    }

    @Test
    void uneArriveeDeclareePuisValidee_seLitSurLaLigneDeLinscrit() {
        Scene s = scene();
        declarer(s);

        assertThat(arrivalDe(s).get("state")).isEqualTo("CLAIMED");
        assertThat(arrivalDe(s).get("claimedAt")).isNotNull();
        assertThat(arrivalDe(s).get("confirmedAt")).isNull();

        valider(s);

        assertThat(arrivalDe(s).get("state")).isEqualTo("CONFIRMED");
        assertThat(arrivalDe(s).get("confirmedAt")).isNotNull();
    }

    /**
     * L'insigne survit à la clôture de la veille : sinon il disparaîtrait de
     * l'écran de l'organisateur au moment précis où la personne rentre chez elle.
     */
    @Test
    void linsigne_resteLisibleApresLaClotureDeLaVeille() {
        Scene s = scene();
        declarer(s);
        valider(s);

        String code = String.valueOf(webTestClient.post()
            .uri("/api/watches/{id}/code/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
        webTestClient.post().uri("/api/watches/{id}/close", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();

        assertThat(arrivalDe(s).get("state")).isEqualTo("CONFIRMED");
    }

    /**
     * Les deux populations doivent rester disjointes, ce que le client tenait pour
     * acquis « par construction ». Ce n'est vrai que depuis ce filtre : une
     * déclaration laisse la veille en {@code ARMED}, donc dans le champ de
     * {@code pending-arrivals}, et le même nom aurait porté les deux gestes à la
     * fois — « je la vois » et « valider sa présence ».
     */
    @Test
    void quiADeclare_neFigurePlusParmiLesArriveesAttendues() {
        Scene s = scene();
        assertThat(attendus(s)).hasSize(1);

        declarer(s);

        assertThat(attendus(s)).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void declarer(Scene s) {
        webTestClient.post().uri("/api/watches/{id}/arrival/claim", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isAccepted();
    }

    private void valider(Scene s) {
        webTestClient.post()
            .uri("/api/schedules/{s}/arrivals/{p}/confirm", s.scheduleId(), s.participationId())
            .headers(h -> h.setBearerAuth(s.hote().token()))
            .exchange().expectStatus().isAccepted();
    }

    private Map<?, ?> veille(Scene s) {
        Map<?, ?> detail = webTestClient.get().uri("/api/watches/{id}", s.watchId())
            .headers(h -> h.setBearerAuth(s.participant().token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<?, ?>) detail.get("watch");
    }

    /** Le bloc d'arrivée de l'inscrit, tel que l'organisateur le lit. */
    private Map<?, ?> arrivalDe(Scene s) {
        List<?> inscrits = webTestClient.get()
            .uri("/api/slots/{id}/participants", s.scheduleId())
            .headers(h -> h.setBearerAuth(s.hote().token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
        return inscrits.stream()
            .map(l -> (Map<?, ?>) l)
            .filter(l -> s.participationId().toString().equals(String.valueOf(l.get("participationId"))))
            .map(l -> (Map<?, ?>) l.get("arrival"))
            .findFirst().orElseThrow();
    }

    private List<?> attendus(Scene s) {
        return webTestClient.get().uri("/api/schedules/{id}/pending-arrivals", s.scheduleId())
            .headers(h -> h.setBearerAuth(s.hote().token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
    }

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private void reculerBaseAller(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setOutboundBaseAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /** Vieillit la déclaration, pour donner sa chance à la bascule automatique. */
    private void reculerDeclaration(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setArrivalClaimedAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /**
     * Un créneau, son organisateur, un inscrit distinct, et la veille de l'inscrit.
     *
     * <p>L'organisateur et la personne veillée doivent être deux comptes : c'est
     * tout le sujet du lot — un tiers acquiert un geste sur le parcours de
     * quelqu'un d'autre.
     */
    private record Scene(Compte hote, Compte participant, UUID scheduleId,
                         UUID participationId, UUID watchId) {}

    private Scene scene() {
        Compte hote = compte("hote");
        Compte participant = compte("inscrit");
        UUID scheduleId = creerCreneau(hote);
        UUID participationId = rejoindre(participant, hote, scheduleId);
        UUID guardianId = contactAccepte(participant);
        UUID watchId = armer(participant, scheduleId, guardianId);
        return new Scene(hote, participant, scheduleId, participationId, watchId);
    }

    /** La même scène, sans veille : l'inscrit n'a rien armé. */
    private Scene sceneSansVeille() {
        Compte hote = compte("hote");
        Compte participant = compte("inscrit");
        UUID scheduleId = creerCreneau(hote);
        UUID participationId = rejoindre(participant, hote, scheduleId);
        return new Scene(hote, participant, scheduleId, participationId, null);
    }

    /**
     * Rejoint le créneau et rend l'identifiant de participation.
     *
     * <p>La liste des inscrits est relue avec le jeton de l'organisateur : elle lui
     * est réservée, et c'est bien lui qui, dans le produit, y lit les lignes et les
     * identifiants que vise le verbe de validation.
     */
    private UUID rejoindre(Compte qui, Compte hote, UUID scheduleId) {
        webTestClient.post().uri("/api/slots/{id}/join", scheduleId)
            .headers(h -> h.setBearerAuth(qui.token()))
            .exchange().expectStatus().isCreated();

        List<?> inscrits = webTestClient.get().uri("/api/slots/{id}/participants", scheduleId)
            .headers(h -> h.setBearerAuth(hote.token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(inscrits.stream()
            .map(l -> (Map<?, ?>) l)
            .filter(l -> qui.id().toString().equals(
                String.valueOf(((Map<?, ?>) l.get("user")).get("id"))))
            .findFirst().orElseThrow().get("participationId")));
    }

    private UUID armer(Compte owner, UUID scheduleId, UUID guardianId) {
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                              "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private UUID creerCreneau(Compte owner) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.HOURS), null,
                "Studio Lumière", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(body.get("scheduleId")));
    }

    private UUID contactAccepte(Compte owner) {
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "email", "proche-" + suffixe + "@example.org"))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return guardianId;
    }

    private record Compte(UUID id, String token) {}

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Deux" + UUID.randomUUID().toString().substring(0, 8)))
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

        return new Compte(id, auth.accessToken());
    }
}
