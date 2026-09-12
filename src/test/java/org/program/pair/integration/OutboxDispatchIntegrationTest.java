package org.program.pair.integration;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.outbox.MessageAEnvoyer;
import org.program.pair.domain.outbox.OutboxClaimer;
import org.program.pair.domain.outbox.OutboxConfirmer;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.outbox.OutboxStatus;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationEmailDelivery;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.observabilite.MetriquesOutbox;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * L'envoi d'un message d'outbox : réclamé sous verrou, remis hors transaction,
 * confirmé message par message (P-BA-03, lots 0 et 1).
 *
 * <p>Ce que ces tests ferment, dans l'ordre où les défauts ont été trouvés :
 * <ul>
 *   <li><b>lot 0</b> — le balayage passe toutes les dix secondes et, tant qu'un
 *       refus ne posait pas de date de prochain essai, les cinq essais d'un
 *       message s'épuisaient en moins d'une minute. Une panne fournisseur d'une
 *       minute suffisait à déclarer <b>définitivement en échec</b> l'alerte
 *       disant qu'un proche n'est pas rentré ;</li>
 *   <li><b>lot 1</b> — tout le lot tenait dans une seule transaction, avec les
 *       appels fournisseur dedans : une exception sur un message annulait les
 *       envois déjà acceptés des autres, et aucune lecture n'était verrouillée,
 *       de sorte que deux instances remettaient le même message deux fois.</li>
 * </ul>
 *
 * <p><b>Le fournisseur est bouché par sa propre configuration</b>, comme dans
 * {@code VerificationEmailDeliveryIntegrationTest} : sous le profil de test,
 * {@code resend.enabled} est faux et {@code sendHtmlEmailReturningId} rend
 * {@code null} — soit exactement un envoi refusé — et {@code NoOpSmsService}
 * rend un {@code refused}. Aucun {@code @MockitoBean} n'est donc nécessaire, et
 * cette classe n'ajoute pas un contexte Spring de plus à la suite (le cache est
 * à son plafond de quatre).
 *
 * <p><b>Le temps est passé en paramètre, jamais attendu.</b>
 * {@link OutboxClaimer#reclamer} et {@link OutboxConfirmer#confirmer} reçoivent
 * l'instant du balayage : une panne de trente minutes et deux heures et demie
 * d'essais se rejouent donc en quelques millisecondes, sur la vraie requête de
 * réclamation et la vraie écriture de confirmation — et non sur une simulation
 * en mémoire.
 *
 * <p><b>La base est partagée par toute la suite, et le balayage de fond tourne
 * dedans.</b> {@code OutboxSweepJob} passe toutes les dix secondes, y compris
 * pendant ces tests : il réclame et refuse tout ce qui est éligible <i>à
 * l'instant réel</i>. Deux précautions en découlent, et elles expliquent la
 * forme des tests ci-dessous :
 * <ul>
 *   <li>les messages dont le compte d'essais est observé sont rendus éligibles à
 *       un instant <b>situé dans deux heures</b> ({@link #ECHEANCE_HORS_SUITE}) :
 *       le balayage de fond ne les voit jamais, et seuls les appels explicites du
 *       test les prennent. La suite entière dure une dizaine de minutes, la marge
 *       est large ;</li>
 *   <li>ces messages portent {@link #PRIORITE_DEVANT}, plus petite que toute
 *       priorité produite en production : à l'instant simulé, les messages
 *       laissés par les autres classes sont eux aussi éligibles, et c'est l'ordre
 *       {@code (priorité, ancienneté)} de la réclamation qui garantit qu'un
 *       {@code reclamer(1, …)} rend celui du test et pas le leur.</li>
 * </ul>
 * Chaque test crée ses propres lignes et {@link #effacerLesMessagesDuTest()} les
 * efface une par une, par identifiant : rien ne fuit vers la classe suivante.
 */
class OutboxDispatchIntegrationTest extends AbstractIntegrationTest {

    /**
     * Plus petit que {@link OutboxService#PRIORITE_ALERTE} : réservé aux
     * messages de ce test, pour qu'une réclamation les prenne avant ceux qu'une
     * autre classe a laissés en attente.
     */
    private static final int PRIORITE_DEVANT = -1;

    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired UserRepository userRepository;
    @Autowired OutboxService outboxService;
    @Autowired OutboxClaimer claimer;
    @Autowired OutboxConfirmer confirmer;
    @Autowired DataSource dataSource;

    /**
     * Le registre du contexte, pour lire les compteurs de {@link MetriquesOutbox}.
     *
     * <p>Lus en <b>écart</b> et jamais en valeur absolue : le balayage de fond
     * alimente les mêmes séries pendant toute la suite, et une classe qui a tourné
     * avant celle-ci en a laissé sa part.
     */
    @Autowired MeterRegistry registreDeMesures;

    /**
     * L'instant à partir duquel les messages de ce test sont éligibles : hors de
     * portée du balayage de fond, qui travaille à l'heure réelle.
     *
     * <p>Tronqué à la milliseconde : {@code TIMESTAMPTZ} garde les microsecondes,
     * et une date d'échéance comparée au nanoseconde près échouerait après un
     * aller-retour en base.
     */
    private static final Instant ECHEANCE_HORS_SUITE =
        Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

    private final List<UUID> messagesDuTest = new ArrayList<>();

    @AfterEach
    void effacerLesMessagesDuTest() {
        // Un par un, par identifiant. Les lignes de ce test sont éligibles dans
        // deux heures et portent une priorité négative : les laisser en base
        // ferait dépendre les réclamations de la classe suivante de l'ordre des
        // tests.
        outboxRepository.deleteAll(outboxRepository.findAllById(messagesDuTest));
        messagesDuTest.clear();
    }

    @Test
    void unEnvoiRefuse_repousseLeMessage_selonUnDelaiCroissant() {
        // Relevé avant le dépôt : le balayage périodique tourne aussi dans le
        // contexte de test, et peut faire le premier essai avant cet appel. Tout
        // essai a nécessairement lieu après cet instant, l'assertion sur le
        // délai tient donc quel que soit celui qui l'a fait.
        Instant avant = Instant.now();
        OutboxMessage depose = deposer(alerte());

        outboxService.dispatchPending();

        // Sous Awaitility, et non en lecture directe : ce message est éligible à
        // l'instant réel, donc le balayage de fond peut l'avoir pris en même
        // temps. On attend l'état d'après l'essai plutôt que d'attraper un
        // SENDING de passage — l'assertion porte sur ce qu'un refus laisse, pas
        // sur qui a fait l'essai.
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(50))
            .untilAsserted(() -> {
                OutboxMessage encours = relire(depose);
                assertThat(encours.getStatus())
                    .as("refusé, il retourne en attente — et non dans un SENDING dont personne ne sort")
                    .isEqualTo(OutboxStatus.PENDING);
                assertThat(encours.getAttempts()).isGreaterThanOrEqualTo(1);
                assertThat(encours.getLockedUntil())
                    .as("le verrou est rendu avec la confirmation")
                    .isNull();
                // Le balayage repasse dans dix secondes ; le message, lui, attend trente.
                assertThat(encours.getNextAttemptAt())
                    .as("la date du prochain essai est posée, et au-delà du prochain balayage")
                    .isNotNull()
                    .isAfter(avant.plusSeconds(25));
            });

        // Et un balayage immédiat ne le reprend pas : c'est le filtre de la
        // réclamation sur next_attempt_at qui le retient, pas un hasard de tri.
        int essaisApresLePremierRefus = relire(depose).getAttempts();
        outboxService.dispatchPending();
        assertThat(relire(depose).getAttempts()).isEqualTo(essaisApresLePremierRefus);
    }

    @Test
    void desRefusSuccessifs_doublentLeDelaiDuProchainEssai_jusquaUnPlafond() {
        OutboxMessage message = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());

        List<Duration> delais = new ArrayList<>();
        Instant horloge = ECHEANCE_HORS_SUITE;
        for (int refus = 0; refus < 7; refus++) {
            refuser(message, horloge);
            Instant prochain = relire(message).getNextAttemptAt();
            delais.add(Duration.between(horloge, prochain));
            horloge = prochain;
        }

        assertThat(delais)
            .as("30 s, puis le double à chaque essai, plafonné à 30 min — sans quoi "
                + "le dixième essai tomberait plus de quatre heures après le dépôt")
            .containsExactly(
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofMinutes(2),
                Duration.ofMinutes(4),
                Duration.ofMinutes(8),
                Duration.ofMinutes(16),
                Duration.ofMinutes(30));
        assertThat(relire(message).getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void unFournisseurEnPanneTrenteMinutes_neRendAucunMessageDefinitivementEnEchec() {
        // La panne est rejouée à horloge maîtrisée : chaque essai a lieu à
        // l'instant même où le message redevient éligible, c'est-à-dire au plus
        // tôt où un balayage pourrait le reprendre. C'est donc le pire cas —
        // celui qui consomme le plus d'essais pendant les trente minutes.
        OutboxMessage message = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());
        Instant finDeLaPanne = ECHEANCE_HORS_SUITE.plus(30, ChronoUnit.MINUTES);

        Instant horloge = ECHEANCE_HORS_SUITE;
        int essais = 0;
        while (horloge.isBefore(finDeLaPanne)) {
            refuser(message, horloge);
            essais++;

            OutboxMessage relu = relire(message);
            assertThat(relu.getStatus())
                .as("après %d essais et %d s de panne, le message doit rester à envoyer",
                    essais, Duration.between(ECHEANCE_HORS_SUITE, horloge).toSeconds())
                .isEqualTo(OutboxStatus.PENDING);
            assertThat(relu.getAttempts())
                .as("un essai par réclamation, ni plus ni moins")
                .isEqualTo(essais);
            horloge = relu.getNextAttemptAt();
        }

        assertThat(essais)
            .as("trente minutes de panne ne doivent pas consommer les dix essais")
            .isLessThan(OutboxService.MAX_ESSAIS);
    }

    @Test
    void leDixiemeEchec_marqueLeMessageEnEchec_etLEmailDeVerificationDuCompteAussi() {
        User compte = compte();
        assertThat(compte.getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.PENDING);

        OutboxMessage message = deposerEligibleA(ECHEANCE_HORS_SUITE,
            OutboxMessage.verificationEmail(compte.getId(), compte.getEmail(),
                "Vérifiez votre adresse", "<p>lien</p>", PRIORITE_DEVANT));

        double refusAvant = compteur(MetriquesOutbox.COMPTEUR_REFUS, "EMAIL", "EMAIL_VERIFICATION");
        double echecsAvant = compteur(MetriquesOutbox.COMPTEUR_ECHECS, "EMAIL", "EMAIL_VERIFICATION");

        Instant horloge = ECHEANCE_HORS_SUITE;
        for (int essai = 1; essai <= OutboxService.MAX_ESSAIS; essai++) {
            refuser(message, horloge);

            OutboxMessage relu = relire(message);
            assertThat(relu.getAttempts()).isEqualTo(essai);
            if (essai < OutboxService.MAX_ESSAIS) {
                assertThat(relu.getStatus()).isEqualTo(OutboxStatus.PENDING);
                assertThat(recharger(compte).getVerificationEmailDelivery())
                    .as("tant qu'il reste des essais, PENDING est exact : afficher un "
                        + "échec inviterait à corriger une adresse qui n'a rien")
                    .isEqualTo(VerificationEmailDelivery.PENDING);
                horloge = relu.getNextAttemptAt();
            }
        }

        assertThat(relire(message).getStatus())
            .as("dix essais épuisés : l'échec est terminal, et fait pour être vu")
            .isEqualTo(OutboxStatus.FAILED);
        assertThat(relire(message).getLockedUntil()).isNull();
        assertThat(recharger(compte).getVerificationEmailDelivery())
            .as("l'écran du compte doit dire l'échec, pas rester sur une attente sans fin")
            .isEqualTo(VerificationEmailDelivery.FAILED);

        assertThat(compteur(MetriquesOutbox.COMPTEUR_REFUS, "EMAIL", "EMAIL_VERIFICATION"))
            .as("chaque refus se voit tout de suite : c'est ce compteur qu'on regarde "
                + "pendant une panne, et non outbox.failed, qui ne bouge que deux "
                + "heures plus tard")
            .isGreaterThanOrEqualTo(refusAvant + OutboxService.MAX_ESSAIS - 1);
        assertThat(compteur(MetriquesOutbox.COMPTEUR_ECHECS, "EMAIL", "EMAIL_VERIFICATION"))
            .as("un abandon, un seul point compté — les neuf refus précédents ne sont pas des échecs")
            .isEqualTo(echecsAvant + 1);
    }

    /**
     * Deux balayages simultanés, et deux messages : chacun en prend un, et jamais
     * le même.
     *
     * <p>C'est le défaut que le lot 1 ferme. Sans {@code FOR UPDATE SKIP LOCKED}
     * ni état intermédiaire, deux instances lisaient les mêmes lignes
     * {@code PENDING} et remettaient le même message deux fois — un proche
     * recevait deux fois le même SMS d'alerte.
     *
     * <p>Deux messages plutôt qu'un, et un lot de <b>un</b> par balayage : le
     * second balayage a ainsi quelque chose à prendre, et ce quelque chose
     * appartient encore au test. Avec un seul message, il aurait réclamé — et
     * verrouillé pour deux minutes — la ligne d'une autre classe de la suite.
     */
    @Test
    void deuxBalayagesConcurrents_neReclamentJamaisLeMemeMessage() throws Exception {
        OutboxMessage premier = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());
        OutboxMessage second = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());

        double reclamesAvant = compteurSansEtiquette(MetriquesOutbox.COMPTEUR_RECLAMES);

        CountDownLatch depart = new CountDownLatch(1);
        Callable<List<UUID>> balayage = () -> {
            assertThat(depart.await(10, TimeUnit.SECONDS)).isTrue();
            return claimer.reclamer(1, ECHEANCE_HORS_SUITE);
        };

        ExecutorService fils = Executors.newFixedThreadPool(2);
        List<UUID> lotA;
        List<UUID> lotB;
        try {
            Future<List<UUID>> a = fils.submit(balayage);
            Future<List<UUID>> b = fils.submit(balayage);
            depart.countDown();
            lotA = a.get(30, TimeUnit.SECONDS);
            lotB = b.get(30, TimeUnit.SECONDS);
        } finally {
            fils.shutdownNow();
        }

        assertThat(lotA).as("un lot de un rend un message").hasSize(1);
        assertThat(lotB).hasSize(1);
        assertThat(lotA)
            .as("le second balayage a sauté la ligne verrouillée au lieu de la reprendre")
            .doesNotContainAnyElementsOf(lotB);
        assertThat(concat(lotA, lotB))
            .containsExactlyInAnyOrder(premier.getId(), second.getId());

        assertThat(relire(premier).getAttempts())
            .as("un essai, compté une seule fois, à la réclamation")
            .isEqualTo(1);
        assertThat(relire(second).getAttempts()).isEqualTo(1);
        assertThat(relire(premier).getStatus()).isEqualTo(OutboxStatus.SENDING);

        assertThat(compteurSansEtiquette(MetriquesOutbox.COMPTEUR_RECLAMES))
            .as("les deux réclamations sont mesurées : l'immobilité de ce compteur est "
                + "le seul signe qu'aucun balayage ne tourne plus")
            .isGreaterThanOrEqualTo(reclamesAvant + 2);

        // On rend la main proprement : les deux messages repartiront comme après
        // n'importe quel refus.
        refuserSansReclamer(premier, ECHEANCE_HORS_SUITE);
        refuserSansReclamer(second, ECHEANCE_HORS_SUITE);
    }

    /**
     * Un balayage tué entre la réclamation et la confirmation ne bloque pas le
     * message : son verrou expire, et le balayage suivant le reprend.
     *
     * <p>C'est la contrepartie assumée de l'état {@code SENDING} : sans échéance,
     * un conteneur tué en plein envoi laisserait une alerte figée pour toujours.
     * L'essai, lui, est compté — c'est ce qui borne « au moins une fois ».
     */
    @Test
    void unMessageReclameParUnBalayageInterrompu_redevientEligible_apresExpirationDuVerrou() {
        OutboxMessage message = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());
        // Le témoin donne au balayage de la deuxième phase quelque chose à
        // prendre qui appartient au test, plutôt que la ligne d'une autre classe.
        // Il n'est éligible que trente secondes plus tard : le premier balayage
        // ne peut donc prendre que le message, sans dépendre de l'ordre de deux
        // dates de création séparées par un aller-retour en base.
        OutboxMessage temoin = deposerEligibleA(
            ECHEANCE_HORS_SUITE.plusSeconds(30), alerte());

        assertThat(claimer.reclamer(1, ECHEANCE_HORS_SUITE))
            .as("le seul message du test éligible à cet instant")
            .containsExactly(message.getId());

        // Le balayage meurt ici : aucune confirmation n'arrive.
        OutboxMessage reclame = relire(message);
        assertThat(reclame.getStatus()).isEqualTo(OutboxStatus.SENDING);
        assertThat(reclame.getAttempts())
            .as("un crash en plein envoi compte pour un essai")
            .isEqualTo(1);
        assertThat(reclame.getLockedUntil())
            .isEqualTo(ECHEANCE_HORS_SUITE.plus(OutboxClaimer.VERROU));

        // Pendant le verrou, personne ne le reprend : le balayage suivant tombe
        // sur le témoin.
        Instant pendantLeVerrou = ECHEANCE_HORS_SUITE.plus(1, ChronoUnit.MINUTES);
        assertThat(claimer.reclamer(1, pendantLeVerrou))
            .as("un message réclamé n'est pas repris tant que son verrou tient")
            .containsExactly(temoin.getId());
        assertThat(relire(message).getAttempts()).isEqualTo(1);

        // Le verrou expiré, il redevient éligible — et de lui-même.
        Instant apresLeVerrou = ECHEANCE_HORS_SUITE.plus(OutboxClaimer.VERROU).plusSeconds(1);
        assertThat(claimer.reclamer(1, apresLeVerrou))
            .as("le verrou dépassé rend le message au balayage, sans nettoyage")
            .containsExactly(message.getId());
        assertThat(relire(message).getAttempts()).isEqualTo(2);

        refuserSansReclamer(message, apresLeVerrou);
        refuserSansReclamer(temoin, apresLeVerrou);
    }

    /**
     * Aucune connexion n'est tenue pendant l'appel au fournisseur.
     *
     * <p>Le test se place exactement là où {@code dispatchPending} appelle le
     * fournisseur : après la réclamation, avant la confirmation. Deux choses s'y
     * vérifient, et elles étaient toutes deux fausses avant le lot 1, quand le
     * lot entier tenait dans une transaction :
     * <ul>
     *   <li>la réclamation est <b>validée</b> — une autre transaction voit le
     *       message en {@code SENDING}. Si l'envoi partageait la transaction du
     *       lot, cet état ne serait visible de personne ;</li>
     *   <li>le pool de connexions redescend à <b>zéro connexion active</b>. Une
     *       transaction ouverte pendant un appel HTTP de plusieurs secondes en
     *       retiendrait une, et cinquante appels en retiendraient une pendant tout
     *       le lot.</li>
     * </ul>
     *
     * <p>La lecture de ce qu'il faut envoyer se fait au passage, et c'est le
     * troisième point : elle rend une projection et non une entité, de sorte
     * qu'elle ne peut pas lever de {@code LazyInitializationException} hors
     * transaction — {@code open-in-view} ne couvre pas les jobs.
     */
    @Test
    void aucuneConnexionNEstTenue_pendantLAppelAuFournisseur() {
        OutboxMessage message = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());

        assertThat(claimer.reclamer(1, ECHEANCE_HORS_SUITE)).containsExactly(message.getId());

        assertThat(relire(message).getStatus())
            .as("la réclamation est validée avant l'appel au fournisseur")
            .isEqualTo(OutboxStatus.SENDING);

        MessageAEnvoyer aEnvoyer = outboxRepository
            .findAEnvoyer(message.getId(), OutboxStatus.SENDING)
            .orElseThrow();
        assertThat(aEnvoyer.recipient()).isEqualTo(message.getRecipient());
        assertThat(aEnvoyer.body()).isEqualTo(message.getBody());

        HikariPoolMXBean pool = poolHikari();
        await().atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(20))
            .untilAsserted(() -> assertThat(pool.getActiveConnections())
                .as("rien n'est tenu pendant que le fournisseur répondrait")
                .isZero());

        refuserSansReclamer(message, ECHEANCE_HORS_SUITE);
        assertThat(relire(message).getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    /**
     * Un message déjà remis n'est pas renvoyé parce que la confirmation d'un
     * autre a échoué.
     *
     * <p>C'était le défaut le plus coûteux du lot unique : les cinquante messages
     * partageaient une transaction, et une exception sur le dernier annulait les
     * {@code markSent} des précédents — <b>que le fournisseur avait acceptés</b>.
     * Ils repartaient au balayage suivant, et les proches recevaient deux fois la
     * même alerte.
     *
     * <p>La confirmation qui échoue est produite par un identifiant fournisseur
     * plus long que sa colonne : un vrai échec d'écriture, au commit, et non une
     * exception simulée.
     */
    @Test
    void unMessageDejaRemis_nEstPasRenvoye_quandLaConfirmationDUnAutreEchoue() {
        OutboxMessage remis = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());
        OutboxMessage voisin = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());

        assertThat(claimer.reclamer(2, ECHEANCE_HORS_SUITE))
            .containsExactlyInAnyOrder(remis.getId(), voisin.getId());

        double partisAvant = compteur(MetriquesOutbox.COMPTEUR_PARTIS, "EMAIL", "WATCH_ALERT");
        String identifiantResend = "resend-" + UUID.randomUUID();
        confirmer.confirmer(remis.getId(),
            OutboxConfirmer.Resultat.accepte(identifiantResend), ECHEANCE_HORS_SUITE);
        assertThat(relire(remis).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(compteur(MetriquesOutbox.COMPTEUR_PARTIS, "EMAIL", "WATCH_ALERT"))
            .as("le seul envoi accepté de la suite : sous le profil de test, le "
                + "fournisseur refuse tout le reste")
            .isEqualTo(partisAvant + 1);

        assertThatThrownBy(() -> confirmer.confirmer(voisin.getId(),
            OutboxConfirmer.Resultat.accepte("x".repeat(200)), ECHEANCE_HORS_SUITE))
            .as("un identifiant fournisseur trop long fait échouer l'écriture, au commit")
            .isInstanceOf(RuntimeException.class);

        OutboxMessage relu = relire(remis);
        assertThat(relu.getStatus())
            .as("l'échec du voisin ne défait pas un envoi que le fournisseur a accepté")
            .isEqualTo(OutboxStatus.SENT);
        assertThat(relu.getProviderMessageId()).isEqualTo(identifiantResend);
        assertThat(relire(voisin).getStatus())
            .as("le voisin, lui, reste réclamé : sa transaction seule a été annulée")
            .isEqualTo(OutboxStatus.SENDING);

        // Et le balayage suivant, verrou expiré, ne reprend que le voisin.
        Instant apresLeVerrou = ECHEANCE_HORS_SUITE.plus(OutboxClaimer.VERROU).plusSeconds(1);
        assertThat(claimer.reclamer(1, apresLeVerrou))
            .as("un message parti est terminal : aucune réclamation ne le rattrape")
            .containsExactly(voisin.getId());

        refuserSansReclamer(voisin, apresLeVerrou);
    }

    /**
     * Une confirmation tardive ne ramène pas un message parti dans la file.
     *
     * <p>Le cas se produit quand un verrou expire : un second balayage reprend le
     * message et l'envoie, puis le premier — qui n'était que lent — confirme son
     * propre refus. Sans garde, ce refus ramènerait à {@code PENDING} un message
     * déjà remis, qui repartirait une troisième fois.
     */
    @Test
    void uneConfirmationTardive_neRamenePasEnAttente_unMessageDejaParti() {
        OutboxMessage message = deposerEligibleA(ECHEANCE_HORS_SUITE, alerte());
        assertThat(claimer.reclamer(1, ECHEANCE_HORS_SUITE)).containsExactly(message.getId());

        String identifiantResend = "resend-" + UUID.randomUUID();
        confirmer.confirmer(message.getId(),
            OutboxConfirmer.Resultat.accepte(identifiantResend), ECHEANCE_HORS_SUITE);

        confirmer.confirmer(message.getId(),
            OutboxConfirmer.Resultat.refuse("le balayage lent revient"), ECHEANCE_HORS_SUITE);

        OutboxMessage relu = relire(message);
        assertThat(relu.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(relu.getProviderMessageId()).isEqualTo(identifiantResend);
        assertThat(relu.getNextAttemptAt())
            .as("aucune date de prochain essai n'est posée sur un message parti")
            .isNull();
    }

    // ------------------------------------------------------------------ outils

    /** Une réclamation puis un refus, à l'instant donné : un essai complet. */
    private void refuser(OutboxMessage message, Instant quand) {
        assertThat(claimer.reclamer(1, quand))
            .as("le message du test est le plus prioritaire des messages éligibles")
            .containsExactly(message.getId());
        refuserSansReclamer(message, quand);
    }

    private void refuserSansReclamer(OutboxMessage message, Instant quand) {
        confirmer.confirmer(message.getId(),
            OutboxConfirmer.Resultat.refuse("fournisseur désactivé sous le profil de test"),
            quand);
    }

    /**
     * Une alerte à un proche, avec un destinataire qui n'appartient qu'à cet
     * appel — la base est partagée par toute la suite.
     */
    private static OutboxMessage alerte() {
        return OutboxMessage.email(
            "proche-" + UUID.randomUUID() + "@example.org",
            "Alerte retour — meetDo", "<p>...</p>", PRIORITE_DEVANT, null);
    }

    private OutboxMessage deposer(OutboxMessage message) {
        OutboxMessage depose = outboxRepository.saveAndFlush(message);
        messagesDuTest.add(depose.getId());
        return depose;
    }

    /**
     * Dépose un message que seul le test pourra réclamer, à partir de
     * {@code quand}.
     *
     * <p>La date du prochain essai n'a pas de mutateur, et c'est voulu : elle est
     * la conséquence d'un refus, pas un réglage. Un refus daté de trente secondes
     * plus tôt la pose donc exactement sur {@code quand}, puisque le premier
     * délai vaut trente secondes — et il ne consomme aucun essai, le comptage
     * ayant lieu à la réclamation.
     */
    private OutboxMessage deposerEligibleA(Instant quand, OutboxMessage message) {
        message.markAttemptFailed(quand.minusSeconds(30), OutboxService.MAX_ESSAIS);
        OutboxMessage depose = deposer(message);
        assertThat(depose.getNextAttemptAt()).isEqualTo(quand);
        assertThat(depose.getAttempts()).isZero();
        return depose;
    }

    private OutboxMessage relire(OutboxMessage message) {
        return outboxRepository.findById(message.getId()).orElseThrow();
    }

    private static List<UUID> concat(List<UUID> a, List<UUID> b) {
        List<UUID> tous = new ArrayList<>(a);
        tous.addAll(b);
        return tous;
    }

    /**
     * Un compteur de {@link MetriquesOutbox}, ou zéro s'il n'a pas encore de
     * série : une série absente et une série à zéro se valent ici.
     */
    private double compteur(String nom, String canal, String objet) {
        Counter compteur = registreDeMesures.find(nom)
            .tag(MetriquesOutbox.ETIQUETTE_CANAL, canal)
            .tag(MetriquesOutbox.ETIQUETTE_OBJET, objet)
            .counter();
        return compteur == null ? 0d : compteur.count();
    }

    private double compteurSansEtiquette(String nom) {
        Counter compteur = registreDeMesures.find(nom).counter();
        return compteur == null ? 0d : compteur.count();
    }

    private HikariPoolMXBean poolHikari() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        assertThat(pool).isNotNull();
        return pool;
    }

    private User compte() {
        User user = new User();
        user.setEmail(uniqueEmail("outbox-essais"));
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
        user.setDisplayName("Compte essais outbox");
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);
        user.setIsActive(true);
        user.setVerificationEmailDelivery(VerificationEmailDelivery.PENDING);
        return userRepository.saveAndFlush(user);
    }

    private User recharger(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
