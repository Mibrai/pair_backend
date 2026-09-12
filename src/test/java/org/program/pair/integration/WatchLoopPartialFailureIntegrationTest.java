package org.program.pair.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.Notification;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchEscalationService;
import org.program.pair.domain.watch.WatchEvent;
import org.program.pair.domain.watch.WatchEventType;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchOutboundJob;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.NotificationRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * L'échec partiel des deux boucles : <b>une veille qui lève ne défait plus le tour
 * des autres</b>, et rien ne part pour un changement d'état non validé.
 *
 * <p><b>Ce qui était cassé, et pourquoi aucun test heureux ne pouvait le voir.</b>
 * Les deux {@code tick()} portaient {@code @Transactional} et rattrapaient chaque
 * {@code RuntimeException} dans leur boucle — ce qui donnait l'illusion d'un
 * isolement. Or {@code WatchEscalationService} est {@code @Transactional} sur la
 * classe : une exception qui traverse son proxy marque la transaction englobante
 * <i>rollback-only</i>, et le commit du job levait une
 * {@code UnexpectedRollbackException}. Tout le passage était perdu —
 * {@code remindersSent}, états, événements, <b>et les lignes d'outbox des veilles
 * saines</b>, donc les messages aux proches de quelqu'un d'autre. Les pushs, elles,
 * étaient déjà parties : {@code notify} est {@code @Async}.
 *
 * <p><b>Pourquoi un {@code @MockitoSpyBean} plutôt qu'un échec par les données.</b>
 * Parce qu'il n'existe aucune donnée qui fasse lever ces deux boucles : le service
 * est défensif à chacun des points que la fiche suggérait, et les colonnes que les
 * boucles écrivent ne portent que des valeurs décidées par le code. Le relevé
 * complet de cette recherche — et le coût réel de la doublure — est en javadoc du
 * champ {@link #escalation}, pour que personne ne la refasse.
 *
 * <p>Comme partout ici, la base est partagée par toute la suite : chaque méthode
 * crée ses comptes, ses créneaux et ses veilles, et n'affirme rien que sur les
 * siens. Les passages déclenchés à la main voient aussi les veilles des autres
 * classes — c'est sans effet, l'espion ne fait échouer que des identifiants qu'il
 * connaît.
 */
class WatchLoopPartialFailureIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired WatchEventRepository watchEventRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;
    @Autowired WatchOutboundJob outboundJob;

    /**
     * L'espion sert à une seule chose : faire lever une veille nommée, et elle seule.
     *
     * <p><b>Il coûte cher, et il reste quand même.</b> C'est le seul
     * {@code @MockitoSpyBean} du dépôt : il donne à cette classe une configuration
     * de contexte Spring à elle, donc un quatrième contexte là où le cache en garde
     * trois ({@code spring.test.context.cache.maxSize=3}) — la suite entière est
     * passée de 9 à 37 minutes, et la machine a paginé jusqu'à provoquer des délais
     * d'attente dans des classes sans rapport. La fiche P-BA-01 demandait donc de
     * <b>provoquer l'échec par les données</b>, « par exemple un {@code guardianId}
     * qui ne pointe plus sur rien », et de ne prendre la doublure qu'à défaut.
     *
     * <p><b>La voie « par les données » a été cherchée et elle est fermée.</b> Ce
     * qui suit est le relevé de la recherche, pour que personne ne la refasse :
     *
     * <ul>
     *   <li><b>{@code guardianId} pendouillant</b> — la suggestion de la fiche.
     *       {@code watches.guardian_id} n'a effectivement pas de clé étrangère (V85
     *       l'explique : un contact retiré ne doit pas effacer l'historique), donc
     *       la donnée est écrivable. Mais {@code prevenirLeContact} commence par
     *       {@code findById(...).orElse(null)} suivi d'un {@code log.warn} et d'un
     *       {@code return} : le cas est traité, rien ne lève. Idem pour
     *       {@code backupGuardianId}.</li>
     *   <li><b>Les gabarits d'alerte</b> — {@code AlertMessages} est null-safe de
     *       bout en bout ({@code escape(null)} rend {@code ""},
     *       {@code GivenName.from(null)} rend {@code null}, {@code titre},
     *       {@code lieuNom}, {@code ville} et {@code heureFin} sont tous gardés), et
     *       les deux instants qu'il formate sans garde — {@code deadlineAt} et
     *       {@code armedAt} — sont {@code NOT NULL} en base, le premier étant en
     *       plus imposé non nul par la requête de balayage.</li>
     *   <li><b>Débordement de colonne à l'{@code INSERT} de l'outbox</b> — il n'y a
     *       pas de jeu : {@code outbox_messages.recipient} est en
     *       {@code VARCHAR(255)}, et les deux sources d'adresse le sont aussi
     *       ({@code guardians.email} 255, {@code users.email} 255). Le sujet est une
     *       constante, le corps est en {@code TEXT}.</li>
     *   <li><b>Les {@code CHECK} de {@code watches}</b> — {@code state} accepte les
     *       neuf états de l'énumération (V99) et {@code reminders_sent BETWEEN 0 AND
     *       3} est hors d'atteinte : la boucle plafonne {@code rappelsDus} à 3 par un
     *       ternaire littéral, donc le compteur n'y monte jamais à 4.</li>
     *   <li><b>L'index unique {@code uq_watches_active_par_creneau}</b> — il ne peut
     *       se violer que si une veille <i>entre</i> dans le champ de l'index, or
     *       aucune transition des boucles ne va d'un état terminal vers un état
     *       vivant ; et le conflit ne peut pas être préparé d'avance, puisque
     *       l'écriture de préparation le violerait elle-même.</li>
     *   <li><b>Clés étrangères</b> — {@code schedule_id} et {@code user_id} en
     *       portent une : on ne peut pas les rendre pendouillants.</li>
     * </ul>
     *
     * <p><b>Le chemin du rappel, en particulier, n'a aucune surface de donnée.</b>
     * {@code sendReminder} ne fait que publier un événement en mémoire, insérer un
     * {@code watch_events} et laisser la boucle écrire {@code state} et
     * {@code reminders_sent} : trois écritures dont <b>toutes</b> les valeurs sont
     * décidées par le code. C'est une bonne nouvelle pour la production et une
     * mauvaise pour ce test.
     *
     * <p>La doublure reste donc, et l'échange est assumé : un contexte de plus
     * contre la seule preuve possible que le tour d'une veille ne défait plus celui
     * des autres. Si le coût devient insupportable, le levier n'est pas d'affaiblir
     * ce test mais de porter {@code spring.test.context.cache.maxSize} à 4, pour que
     * ce quatrième contexte cesse d'évincer les trois autres.
     */
    @MockitoSpyBean WatchEscalationService escalation;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    /**
     * La veille qu'on fait lever, ou {@code null} quand aucune ne doit lever.
     *
     * <p><b>Un champ plutôt qu'un {@code doThrow} posé après coup</b>, et ce n'est
     * pas un détail de style : le planificateur tourne dans les tests
     * ({@code @EnableScheduling}, {@code fixedDelay} d'une minute). Un passage
     * spontané glissé entre « je vieillis l'échéance » et « je pose le piège »
     * aurait avancé la veille censée échouer, et l'échec du test aurait dépendu de
     * la seconde à laquelle il tombe. Les réponses sont donc installées une fois
     * pour toutes, et chaque méthode nomme sa cible <b>avant</b> de rendre quoi que
     * ce soit dû.
     */
    private volatile UUID cibleEnEchec;

    /**
     * Les trois points de sortie que les boucles empruntent passent par une réponse
     * qui délègue au vrai service — sauf pour la cible, où l'appel réel a lieu
     * <b>puis</b> lève. L'ordre compte : le rappel doit être réellement demandé
     * dans la transaction (l'événement de notification est publié) pour qu'on
     * puisse constater qu'il ne part pas quand elle échoue.
     */
    @BeforeEach
    void installerLesPieges() {
        cibleEnEchec = null;
        doAnswer(this::vraiPuisLeverSiCible).when(escalation).sendReminder(any());
        doAnswer(this::vraiPuisLeverSiCible).when(escalation).sendArrivalPrompt(any());
        doAnswer(this::vraiPuisLeverSiCible).when(escalation).ensureAlerted(any(), anyLong());
    }

    private Object vraiPuisLeverSiCible(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        Watch watch = invocation.getArgument(0);
        boolean cible = watch != null && cibleEnEchec != null && cibleEnEchec.equals(watch.getId());
        Object resultat = invocation.callRealMethod();
        if (cible) {
            throw new IllegalStateException("panne simulée sur la veille " + watch.getId());
        }
        return resultat;
    }

    // ------------------------------------------------------------ boucle retour

    @Test
    void uneVeilleQuiLeve_neDoitPasAnnulerLavancementDesAutres() {
        Compte saine = compte();
        Compte malade = compte();
        UUID idSaine = veilleSurPlace(saine);
        UUID idMalade = veilleSurPlace(malade);
        cibleEnEchec = idMalade;
        reculerEcheance(idSaine, 16);
        reculerEcheance(idMalade, 16);

        returnLoopJob.tick();

        // La veille saine a bien avancé, en base : c'est ce que
        // l'UnexpectedRollbackException emportait.
        assertThat(watch(idSaine).getRemindersSent()).isEqualTo(1);
        assertThat(watch(idSaine).getState()).isEqualTo(WatchState.REMINDING);

        // Et celle qui lève n'a rien enregistré : sa transaction, et la sienne
        // seule, a été annulée.
        assertThat(watch(idMalade).getRemindersSent()).isZero();
        assertThat(watch(idMalade).getState()).isEqualTo(WatchState.ON_SITE);
    }

    @Test
    void leRappelDuneVeilleDontLaTransactionEchoue_neDoitPasPartir() {
        Compte saine = compte();
        Compte malade = compte();
        UUID idSaine = veilleSurPlace(saine);
        UUID idMalade = veilleSurPlace(malade);
        // Le rappel est bien demandé dans la transaction — l'événement est publié —
        // puis la transaction échoue. C'est le cas qui compte : avant, la push
        // partait quand même, et la personne recevait « confirme ton retour » pour
        // un compteur de rappels que le rollback venait d'effacer, donc répété au
        // passage suivant.
        cibleEnEchec = idMalade;
        reculerEcheance(idSaine, 16);
        reculerEcheance(idMalade, 16);

        returnLoopJob.tick();

        // La veille saine, elle, notifie : l'écouteur après commit fonctionne.
        await().atMost(Duration.ofSeconds(10))
            .until(() -> !notificationsDe(saine, NotificationType.WATCH_RETURN_REMINDER).isEmpty());

        // Celle qui a échoué ne notifie pas, et on le vérifie dans la durée : sans
        // le « during », l'assertion ne mesurerait que la vitesse du fil asynchrone.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
            .until(() -> notificationsDe(malade, NotificationType.WATCH_RETURN_REMINDER).isEmpty());
    }

    @Test
    void lescaladeDuneVeilleVoisine_doitEtreEnregistree_etSonMessageDepose() {
        Compte saine = compte();
        Compte malade = compte();
        UUID idSaine = veilleSurPlace(saine);
        UUID idMalade = veilleSurPlace(malade);
        // Les trois rappels sont derrière, l'échéance a soixante et une minutes :
        // l'escalade est due pour les deux.
        troisRappelsPasses(idSaine);
        troisRappelsPasses(idMalade);
        cibleEnEchec = idMalade;
        reculerEcheance(idSaine, 61);
        reculerEcheance(idMalade, 61);

        returnLoopJob.tick();

        // L'essentiel : le message au proche de la veille saine est déposé. C'est
        // lui que le rollback global faisait disparaître — un tiers n'était pas
        // prévenu parce que la veille de quelqu'un d'autre avait levé.
        assertThat(watch(idSaine).getState()).isEqualTo(WatchState.ESCALATED);
        assertThat(outboxRepository.findByWatchId(idSaine)).isNotEmpty();

        // Et la veille en échec est restée exactement où la préparation l'avait
        // laissée : REMINDING avec ses trois rappels — et non ESCALATED, que
        // `avancer` pose avant d'appeler `ensureAlerted`. C'est la preuve du
        // rollback : l'état, le message et l'événement ont été défaits ensemble.
        assertThat(watch(idMalade).getState()).isEqualTo(WatchState.REMINDING);
        assertThat(watch(idMalade).getRemindersSent()).isEqualTo(3);
        assertThat(outboxRepository.findByWatchId(idMalade)).isEmpty();
        assertThat(typesDeLaChronologie(idMalade)).doesNotContain(WatchEventType.ESCALATED);
    }

    @Test
    void deuxPassagesSuccessifs_neDoiventPasEnvoyerDeuxFoisLeMemeRappel() {
        Compte moi = compte();
        UUID watchId = veilleSurPlace(moi);
        reculerEcheance(watchId, 16);

        returnLoopJob.tick();
        returnLoopJob.tick();

        // Un seul rappel est dû à T+16 : le compteur et la chronologie le disent
        // tous les deux. L'idempotence tient par passage validé.
        assertThat(watch(watchId).getRemindersSent()).isEqualTo(1);
        assertThat(typesDeLaChronologie(watchId))
            .filteredOn(t -> t == WatchEventType.REMINDER_SENT)
            .hasSize(1);
    }

    // -------------------------------------------------------------- boucle aller

    @Test
    void laBoucleAller_doitIsolerAussiChaqueVeille() {
        Compte saine = compte();
        Compte malade = compte();
        UUID idSaine = veilleArmee(saine);
        UUID idMalade = veilleArmee(malade);
        cibleEnEchec = idMalade;
        reculerBaseAller(idSaine, 16);
        reculerBaseAller(idMalade, 16);

        outboundJob.tick();

        assertThat(watch(idSaine).getArrivalPromptsSent()).isEqualTo(1);
        assertThat(watch(idSaine).getState()).isEqualTo(WatchState.EN_ROUTE);

        assertThat(watch(idMalade).getArrivalPromptsSent()).isZero();
        assertThat(watch(idMalade).getState()).isEqualTo(WatchState.ARMED);
    }

    // ------------------------------------------------------------------ outils

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private void reculerEcheance(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setDeadlineAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    private void reculerBaseAller(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setOutboundBaseAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /** Pose les trois rappels comme déjà partis : l'escalade devient le pas suivant. */
    private void troisRappelsPasses(UUID watchId) {
        Watch w = watch(watchId);
        w.setRemindersSent(3);
        w.setState(WatchState.REMINDING);
        watchRepository.saveAndFlush(w);
    }

    private List<WatchEventType> typesDeLaChronologie(UUID watchId) {
        return watchEventRepository.findByWatchIdOrderByOccurredAtAsc(watchId).stream()
            .map(WatchEvent::getType)
            .toList();
    }

    private List<Notification> notificationsDe(Compte qui, NotificationType type) {
        return notificationRepository.findByUserId(qui.id()).stream()
            .filter(n -> n.getType() == type)
            .toList();
    }

    /** Une veille armée, contact accepté, arrivée validée : elle est {@code ON_SITE}. */
    private UUID veilleSurPlace(Compte owner) {
        UUID watchId = veilleArmee(owner);
        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange().expectStatus().isOk();
        return watchId;
    }

    /** Une veille armée avec un contact accepté joignable, sans arrivée encore. */
    private UUID veilleArmee(Compte owner) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner, uniqueMobile(), uniqueEmail("proche"));
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
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

    private UUID contactAccepte(Compte owner, String phone, String email) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "phone", phone, "email", email))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return guardianId;
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("partiel");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Partiel" + UUID.randomUUID().toString().substring(0, 8)))
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
