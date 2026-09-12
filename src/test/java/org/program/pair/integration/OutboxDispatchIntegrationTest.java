package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.outbox.OutboxStatus;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationEmailDelivery;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le délai entre deux essais d'un message d'outbox (P-BA-03, lot 0).
 *
 * <p>Ce que ces tests ferment : le balayage passe toutes les dix secondes et,
 * tant qu'un refus ne posait pas de date de prochain essai, les cinq essais d'un
 * message s'épuisaient en moins d'une minute. Une panne fournisseur d'une minute
 * suffisait donc à déclarer <b>définitivement en échec</b> l'alerte disant qu'un
 * proche n'est pas rentré. Il n'y avait rien à réparer côté fournisseur : le
 * message était perdu par nous.
 *
 * <p><b>Le fournisseur est bouché par sa propre configuration</b>, comme dans
 * {@code VerificationEmailDeliveryIntegrationTest} : sous le profil de test,
 * {@code resend.enabled} est faux et {@code sendHtmlEmailReturningId} rend
 * {@code null} — soit exactement un envoi refusé — et {@code NoOpSmsService}
 * rend un {@code refused}. Aucun {@code @MockitoBean} n'est donc nécessaire, et
 * cette classe n'ajoute pas un contexte Spring de plus à la suite.
 *
 * <p><b>La base est partagée par toute la suite.</b> Chaque test crée ses
 * propres lignes, et les messages qu'il veut voir passer dans le lot de 50 du
 * balayage portent {@link #PRIORITE_DEVANT} : aucune priorité négative n'est
 * produite en production, ces messages passent donc devant ceux qu'une autre
 * classe a laissés en attente, sans quoi l'assertion dépendrait de l'ordre des
 * classes.
 */
class OutboxDispatchIntegrationTest extends AbstractIntegrationTest {

    /**
     * Plus petit que {@link OutboxService#PRIORITE_ALERTE} : réservé aux
     * messages de ce test, pour qu'ils soient dans le lot que le balayage lit.
     */
    private static final int PRIORITE_DEVANT = -1;

    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired UserRepository userRepository;
    @Autowired OutboxService outboxService;

    @Test
    void unEnvoiRefuse_repousseLeMessage_selonUnDelaiCroissant() {
        // Relevé avant le dépôt : le balayage périodique tourne aussi dans le
        // contexte de test, et peut faire le premier essai avant cet appel. Tout
        // essai a nécessairement lieu après cet instant, l'assertion sur le
        // délai tient donc quel que soit celui qui l'a fait.
        Instant avant = Instant.now();
        OutboxMessage depose = outboxRepository.saveAndFlush(alerte());

        outboxService.dispatchPending();

        OutboxMessage apres = relire(depose);
        assertThat(apres.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(apres.getAttempts()).isGreaterThanOrEqualTo(1);
        // Le balayage repasse dans dix secondes ; le message, lui, attend trente.
        assertThat(apres.getNextAttemptAt())
            .as("la date du prochain essai est posée, et au-delà du prochain balayage")
            .isNotNull()
            .isAfter(avant.plusSeconds(25));

        // Et un balayage immédiat ne le reprend pas : c'est le filtre de lecture
        // sur next_attempt_at qui le retient, pas un hasard de tri.
        int essaisApresLePremierRefus = apres.getAttempts();
        outboxService.dispatchPending();
        assertThat(relire(depose).getAttempts()).isEqualTo(essaisApresLePremierRefus);

        // La croissance du délai se lit sur le message lui-même, à horloge
        // maîtrisée : 30 s, puis le double à chaque essai, plafonné à 30 min.
        Instant t = Instant.parse("2030-01-01T20:00:00Z");
        OutboxMessage suivi = alerte();
        assertThat(delaiApresUnRefus(suivi, t)).isEqualTo(Duration.ofSeconds(30));
        assertThat(delaiApresUnRefus(suivi, t)).isEqualTo(Duration.ofMinutes(1));
        assertThat(delaiApresUnRefus(suivi, t)).isEqualTo(Duration.ofMinutes(2));
        assertThat(delaiApresUnRefus(suivi, t)).isEqualTo(Duration.ofMinutes(4));
        assertThat(delaiApresUnRefus(suivi, t)).isEqualTo(Duration.ofMinutes(8));
        assertThat(delaiApresUnRefus(suivi, t)).isEqualTo(Duration.ofMinutes(16));
        assertThat(delaiApresUnRefus(suivi, t))
            .as("le plafond, pour qu'une alerte ne soit pas remise des heures trop tard")
            .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void unFournisseurEnPanneTrenteMinutes_neRendAucunMessageDefinitivementEnEchec() {
        // La panne est rejouée à horloge maîtrisée : chaque essai a lieu à
        // l'instant même où le message redevient éligible, c'est-à-dire au plus
        // tôt où le balayage pourrait le reprendre. C'est donc le pire cas —
        // celui qui consomme le plus d'essais pendant les trente minutes.
        //
        // Elle est datée loin dans l'avenir à dessein : le message finit sa vie
        // en base avec une date de prochain essai que le balayage périodique de
        // la suite n'atteindra jamais, et ne vient donc pas fausser le compte
        // d'essais relu plus bas.
        OutboxMessage message = alerte();
        Instant debutDeLaPanne = Instant.parse("2030-01-01T22:00:00Z");
        Instant finDeLaPanne = debutDeLaPanne.plus(30, ChronoUnit.MINUTES);

        Instant horloge = debutDeLaPanne;
        int essais = 0;
        while (horloge.isBefore(finDeLaPanne)) {
            message.markAttemptFailed(horloge, OutboxService.MAX_ESSAIS);
            essais++;
            assertThat(message.getStatus())
                .as("après %d essais et %d s de panne, le message doit rester à envoyer",
                    essais, Duration.between(debutDeLaPanne, horloge).toSeconds())
                .isEqualTo(OutboxStatus.PENDING);
            horloge = message.getNextAttemptAt();
        }

        assertThat(essais)
            .as("trente minutes de panne ne doivent pas consommer les dix essais")
            .isLessThan(OutboxService.MAX_ESSAIS);

        // Et l'attente survit à la base : la date est bien une colonne, pas un
        // état gardé en mémoire par le balayage.
        OutboxMessage relu = relire(outboxRepository.saveAndFlush(message));
        assertThat(relu.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(relu.getAttempts()).isEqualTo(essais);
        assertThat(relu.getNextAttemptAt()).isNotNull();
    }

    @Test
    void leDixiemeEchec_marqueLeMessageEnEchec_etLEmailDeVerificationDuCompteAussi() {
        User compte = compte();
        assertThat(compte.getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.PENDING);

        // Neuf refus déjà derrière lui, tous datés d'il y a une heure : le
        // dixième essai est donc dû (30 min de plafond après le neuvième), et
        // c'est celui que le balayage va faire.
        OutboxMessage message = OutboxMessage.verificationEmail(
            compte.getId(), compte.getEmail(), "Vérifiez votre adresse",
            "<p>lien</p>", PRIORITE_DEVANT);
        Instant ilYaUneHeure = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < OutboxService.MAX_ESSAIS - 1; i++) {
            message.markAttemptFailed(ilYaUneHeure, OutboxService.MAX_ESSAIS);
        }
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getNextAttemptAt()).isBefore(Instant.now());
        OutboxMessage depose = outboxRepository.saveAndFlush(message);

        outboxService.dispatchPending();

        OutboxMessage apres = relire(depose);
        assertThat(apres.getAttempts()).isEqualTo(OutboxService.MAX_ESSAIS);
        assertThat(apres.getStatus())
            .as("dix essais épuisés : l'échec est terminal, et fait pour être vu")
            .isEqualTo(OutboxStatus.FAILED);
        assertThat(recharger(compte).getVerificationEmailDelivery())
            .as("l'écran du compte doit dire l'échec, pas rester sur une attente sans fin")
            .isEqualTo(VerificationEmailDelivery.FAILED);
    }

    // ------------------------------------------------------------------ outils

    /** Le délai que ce message s'impose après un refus de plus, daté de {@code t}. */
    private static Duration delaiApresUnRefus(OutboxMessage message, Instant t) {
        message.markAttemptFailed(t, OutboxService.MAX_ESSAIS);
        return Duration.between(t, message.getNextAttemptAt());
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

    private OutboxMessage relire(OutboxMessage message) {
        return outboxRepository.findById(message.getId()).orElseThrow();
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
