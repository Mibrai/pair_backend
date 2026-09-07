package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationEmailDelivery;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La chaîne complète du lot du 07/09 : un e-mail de vérification déposé dans
 * l'outbox, remis au fournisseur, puis rapporté par l'accusé de remise — et
 * l'état qui en résulte sur le compte.
 *
 * <p>C'est le test que le chantier mobile demandait comme recette : « le seul qui
 * prouve que la chaîne est branchée, et non seulement le champ ». Ce qu'il
 * couvre ici sans fournisseur réel, c'est le tronçon qui était rompu — le
 * recoupement entre l'identifiant Resend et le compte, qui n'existait pas parce
 * que l'e-mail de vérification ne passait pas par l'outbox et jetait son
 * identifiant.
 */
class VerificationEmailDeliveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired UserRepository userRepository;
    @Autowired OutboxService outboxService;

    @Test
    void unRebond_faitPasserLeCompte_enBounced() {
        User user = compte("rebond");
        String providerId = envoiRemisAuFournisseur(user);

        webhook(providerId, "email.bounced");

        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.BOUNCED);
    }

    @Test
    void unDelivre_faitPasserLeCompte_enDelivered() {
        User user = compte("delivre");
        String providerId = envoiRemisAuFournisseur(user);

        webhook(providerId, "email.delivered");

        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.DELIVERED);
    }

    @Test
    void unePlainte_compteCommeUnRebond() {
        // Du point de vue de qui attend son lien, un message classé indésirable
        // n'est pas arrivé — même vocabulaire qu'alertDelivery.
        User user = compte("plainte");
        String providerId = envoiRemisAuFournisseur(user);

        webhook(providerId, "email.complained");

        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.BOUNCED);
    }

    @Test
    void unRetard_neFaitPasReculerUnCompteDejaDelivre() {
        User user = compte("retard");
        String providerId = envoiRemisAuFournisseur(user);

        webhook(providerId, "email.delivered");
        webhook(providerId, "email.delivery_delayed"); // arrive après, en retard

        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.DELIVERED);
    }

    @Test
    void unRebondTardif_duRenvoiPrecedent_nEcrasePasLeSortDuNouveau() {
        // Le cas qui justifie de garder l'identifiant sur le compte. Les accusés
        // n'arrivent pas dans l'ordre des faits : sans cette garde, le bouton
        // « renvoyer » aggraverait l'affichage au lieu de le corriger.
        User user = compte("renvoi");

        String ancien = envoiRemisAuFournisseur(user);   // premier envoi
        String nouveau = envoiRemisAuFournisseur(user);  // renvoi : le compte suit celui-ci

        webhook(nouveau, "email.delivered");
        webhook(ancien, "email.bounced"); // concerne un envoi que le compte a remplacé

        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.DELIVERED);
    }

    @Test
    void unEvenementNonSuivi_neChangeRien() {
        User user = compte("ouverture");
        String providerId = envoiRemisAuFournisseur(user);

        webhook(providerId, "email.opened");

        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.SENT);
    }

    @Test
    void unAccuseVisantUneAlerteDeVeille_neTouchePasAuxComptes() {
        // Le report est borné par l'usage du message : une alerte de veille n'a
        // pas de compte, et son rebond ne doit rien écrire ailleurs.
        User user = compte("voisin");
        envoiRemisAuFournisseur(user);
        VerificationEmailDelivery avant = recharge(user).getVerificationEmailDelivery();

        String alerte = "resend-" + UUID.randomUUID();
        OutboxMessage message = OutboxMessage.email(
            "proche@example.org", "Alerte retour — meetDo", "<p>...</p>", 0, null);
        message.markSent(alerte, Instant.now());
        outboxRepository.saveAndFlush(message);

        webhook(alerte, "email.bounced");

        assertThat(recharge(user).getVerificationEmailDelivery()).isEqualTo(avant);
    }

    @Test
    void leDepot_poseLeComptEnPending_etEffaceLIdentifiantPrecedent() {
        User user = compte("depot");
        envoiRemisAuFournisseur(user);
        assertThat(recharge(user).getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.SENT);

        outboxService.enqueueVerificationEmail(
            recharge(user), user.getEmail(), "Vérifiez votre adresse", "<p>lien</p>");

        User apres = recharge(user);
        assertThat(apres.getVerificationEmailDelivery())
            .isEqualTo(VerificationEmailDelivery.PENDING);
        assertThat(apres.getVerificationEmailMessageId()).isNull();
    }

    // ------------------------------------------------------------------ outils

    /**
     * Un e-mail de vérification déposé puis remis au fournisseur, comme le
     * balayage l'aurait fait — sans appeler Resend, qui est désactivé sous le
     * profil de test.
     */
    private String envoiRemisAuFournisseur(User user) {
        String providerId = "resend-" + UUID.randomUUID();
        User courant = recharge(user);

        outboxService.enqueueVerificationEmail(
            courant, courant.getEmail(), "Vérifiez votre adresse", "<p>lien</p>");

        OutboxMessage message = OutboxMessage.verificationEmail(
            courant.getId(), courant.getEmail(), "Vérifiez votre adresse", "<p>lien</p>", 2);
        message.markSent(providerId, Instant.now());
        outboxRepository.saveAndFlush(message);

        courant.setVerificationEmailMessageId(providerId);
        courant.setVerificationEmailDelivery(VerificationEmailDelivery.SENT);
        userRepository.saveAndFlush(courant);

        return providerId;
    }

    private void webhook(String providerId, String type) {
        webTestClient.post().uri("/public/resend-webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"type\":\"" + type + "\",\"data\":{\"email_id\":\"" + providerId + "\"}}")
            .exchange().expectStatus().isOk();
    }

    private User compte(String prefixe) {
        User user = new User();
        user.setEmail(prefixe + "-" + UUID.randomUUID() + "@example.org");
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
        user.setDisplayName("Compte " + prefixe);
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);
        user.setIsActive(true);
        return userRepository.saveAndFlush(user);
    }

    private User recharge(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
